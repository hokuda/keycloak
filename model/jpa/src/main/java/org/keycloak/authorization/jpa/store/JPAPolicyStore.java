/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.authorization.jpa.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.persistence.EntityManager;
import javax.persistence.FlushModeType;
import javax.persistence.NoResultException;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.keycloak.authorization.AuthorizationProvider;
import org.keycloak.authorization.jpa.entities.PolicyEntity;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.StoreFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.authorization.AbstractPolicyRepresentation;
import javax.persistence.LockModeType;

import static org.keycloak.models.jpa.PaginationUtils.paginateQuery;
import static org.keycloak.utils.StreamsUtil.closing;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class JPAPolicyStore implements PolicyStore {

    private final EntityManager entityManager;
    private final AuthorizationProvider provider;
    public JPAPolicyStore(EntityManager entityManager, AuthorizationProvider provider) {
        this.entityManager = entityManager;
        this.provider = provider;
    }

    @Override
    public Policy create(AbstractPolicyRepresentation representation, ResourceServer resourceServer) {
        PolicyEntity entity = new PolicyEntity();

        if (representation.getId() == null) {
            entity.setId(KeycloakModelUtils.generateId());
        } else {
            entity.setId(representation.getId());
        }

        entity.setType(representation.getType());
        entity.setName(representation.getName());
        entity.setResourceServer(ResourceServerAdapter.toEntity(entityManager, resourceServer));

        this.entityManager.persist(entity);
        this.entityManager.flush();
        Policy model = new PolicyAdapter(entity, entityManager, provider.getStoreFactory());
        return model;
    }

    @Override
    public void delete(String id) {
        PolicyEntity policy = entityManager.find(PolicyEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (policy != null) {
            this.entityManager.remove(policy);
        }
    }


    @Override
    public Policy findById(String id, String resourceServerId) {
        if (id == null) {
            return null;
        }

        PolicyEntity policyEntity = entityManager.find(PolicyEntity.class, id);

        if (policyEntity == null) {
            return null;
        }

        return new PolicyAdapter(policyEntity, entityManager, provider.getStoreFactory());
    }

    @Override
    public Policy findByName(String name, String resourceServerId) {
        TypedQuery<PolicyEntity> query = entityManager.createNamedQuery("findPolicyIdByName", PolicyEntity.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("serverId", resourceServerId);
        query.setParameter("name", name);

        try {
            return new PolicyAdapter(query.getSingleResult(), entityManager, provider.getStoreFactory());
        } catch (NoResultException ex) {
            return null;
        }
    }

    @Override
    public List<Policy> findByResourceServer(final String resourceServerId) {
        TypedQuery<String> query = entityManager.createNamedQuery("findPolicyIdByServerId", String.class);

        query.setParameter("serverId", resourceServerId);

        List<String> result = query.getResultList();
        List<Policy> list = new LinkedList<>();
        for (String id : result) {
            Policy policy = provider.getStoreFactory().getPolicyStore().findById(id, resourceServerId);
            if (Objects.nonNull(policy)) {
                list.add(policy);
            }
        }
        return list;
    }

    @Override
    public List<Policy> findByResourceServer(Map<Policy.FilterOption, String[]> attributes, String resourceServerId, int firstResult, int maxResult) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<PolicyEntity> querybuilder = builder.createQuery(PolicyEntity.class);
        Root<PolicyEntity> root = querybuilder.from(PolicyEntity.class);
        List<Predicate> predicates = new ArrayList();
        querybuilder.select(root.get("id"));

        if (resourceServerId != null) {
            predicates.add(builder.equal(root.get("resourceServer").get("id"), resourceServerId));
        }

        attributes.forEach((filterOption, value) -> {
            switch (filterOption) {
                case ID:
                case OWNER:
                    predicates.add(root.get(filterOption.getName()).in(value));
                    break;
                case SCOPE_ID:
                case RESOURCE_ID:
                    String[] predicateValues = filterOption.getName().split("\\.");
                    predicates.add(root.join(predicateValues[0]).get(predicateValues[1]).in(value));
                    break;
                case PERMISSION: {
                    if (Boolean.parseBoolean(value[0])) {
                        predicates.add(root.get("type").in("resource", "scope", "uma"));
                    } else {
                        predicates.add(builder.not(root.get("type").in("resource", "scope", "uma")));
                    }
                }
                    break;
                case OWNER_IS_NOT_NULL:
                    predicates.add(builder.isNotNull(root.get("owner")));
                    break;
                case CONFIG:
                    if (value.length != 2) {
                        throw new IllegalArgumentException("Config filter option requires value with two items: [config_name, expected_config_value]");
                    }

                    predicates.add(root.joinMap("config").key().in(value[0]));
                    predicates.add(builder.like(root.joinMap("config").value().as(String.class), "%" + value[1] + "%"));
                    break;
                case TYPE:
                case NAME:
                    predicates.add(builder.like(builder.lower(root.get(filterOption.getName())), "%" + value[0].toLowerCase() + "%"));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported filter [" + filterOption + "]");
            }
        });

        if (!attributes.containsKey(Policy.FilterOption.OWNER) && !attributes.containsKey(Policy.FilterOption.OWNER_IS_NOT_NULL)) {
            predicates.add(builder.isNull(root.get("owner")));
        }

        querybuilder.where(predicates.toArray(new Predicate[predicates.size()])).orderBy(builder.asc(root.get("name")));

        TypedQuery query = entityManager.createQuery(querybuilder);

        List<String> result = paginateQuery(query, firstResult, maxResult).getResultList();
        List<Policy> list = new LinkedList<>();
        for (String id : result) {
            Policy policy = provider.getStoreFactory().getPolicyStore().findById(id, resourceServerId);
            if (Objects.nonNull(policy)) {
                list.add(policy);
            }
        }
        return list;
    }

    @Override
    public void findByResource(String resourceId, String resourceServerId, Consumer<Policy> consumer) {
        TypedQuery<PolicyEntity> query = entityManager.createNamedQuery("findPolicyIdByResource", PolicyEntity.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("resourceId", resourceId);
        query.setParameter("serverId", resourceServerId);

        PolicyStore storeFactory = provider.getStoreFactory().getPolicyStore();

        closing(query.getResultStream()
                .map(entity -> storeFactory.findById(entity.getId(), resourceServerId))
                .filter(Objects::nonNull))
                .forEach(consumer::accept);
    }

    @Override
    public void findByResourceType(String resourceType, String resourceServerId, Consumer<Policy> consumer) {
        TypedQuery<PolicyEntity> query = entityManager.createNamedQuery("findPolicyIdByResourceType", PolicyEntity.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("type", resourceType);
        query.setParameter("serverId", resourceServerId);

        closing(query.getResultStream()
                .map(id -> new PolicyAdapter(id, entityManager, provider.getStoreFactory()))
                .filter(Objects::nonNull))
                .forEach(consumer::accept);
    }

    @Override
    public List<Policy> findByScopeIds(List<String> scopeIds, String resourceServerId) {
        if (scopeIds==null || scopeIds.isEmpty()) {
            return Collections.emptyList();
        }

        // Use separate subquery to handle DB2 and MSSSQL
        TypedQuery<PolicyEntity> query = entityManager.createNamedQuery("findPolicyIdByScope", PolicyEntity.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("scopeIds", scopeIds);
        query.setParameter("serverId", resourceServerId);

        List<Policy> list = new LinkedList<>();
        PolicyStore storeFactory = provider.getStoreFactory().getPolicyStore();

        for (PolicyEntity entity : query.getResultList()) {
            list.add(storeFactory.findById(entity.getId(), resourceServerId));
        }

        return list;
    }

    @Override
    public void findByScopeIds(List<String> scopeIds, String resourceId, String resourceServerId, Consumer<Policy> consumer) {
        // Use separate subquery to handle DB2 and MSSSQL
        TypedQuery<PolicyEntity> query;

        if (resourceId == null) {
            query = entityManager.createNamedQuery("findPolicyIdByNullResourceScope", PolicyEntity.class);
        } else {
            query = entityManager.createNamedQuery("findPolicyIdByResourceScope", PolicyEntity.class);
            query.setParameter("resourceId", resourceId);
        }

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("scopeIds", scopeIds);
        query.setParameter("serverId", resourceServerId);

        StoreFactory storeFactory = provider.getStoreFactory();

        closing(query.getResultStream()
                .map(id -> new PolicyAdapter(id, entityManager, storeFactory))
                .filter(Objects::nonNull))
                .forEach(consumer::accept);
    }

    @Override
    public List<Policy> findByType(String type, String resourceServerId) {
        TypedQuery<String> query = entityManager.createNamedQuery("findPolicyIdByType", String.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("serverId", resourceServerId);
        query.setParameter("type", type);

        List<String> result = query.getResultList();
        List<Policy> list = new LinkedList<>();
        for (String id : result) {
            Policy policy = provider.getStoreFactory().getPolicyStore().findById(id, resourceServerId);
            if (Objects.nonNull(policy)) {
                list.add(policy);
            }
        }
        return list;
    }

    @Override
    public List<Policy> findDependentPolicies(String policyId, String resourceServerId) {

        TypedQuery<String> query = entityManager.createNamedQuery("findPolicyIdByDependentPolices", String.class);

        query.setFlushMode(FlushModeType.COMMIT);
        query.setParameter("serverId", resourceServerId);
        query.setParameter("policyId", policyId);

        List<String> result = query.getResultList();
        List<Policy> list = new LinkedList<>();
        for (String id : result) {
            Policy policy = provider.getStoreFactory().getPolicyStore().findById(id, resourceServerId);
            if (Objects.nonNull(policy)) {
                list.add(policy);
            }
        }
        return list;
    }
}
