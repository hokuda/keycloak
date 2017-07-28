/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.sessions.infinispan.remotestore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.infinispan.client.hotrod.Flag;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.VersionedValue;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.sessions.infinispan.changes.SessionEntityWrapper;
import org.keycloak.models.sessions.infinispan.changes.SessionUpdateTask;
import org.keycloak.models.sessions.infinispan.entities.SessionEntity;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class RemoteCacheInvoker {

    public static final Logger logger = Logger.getLogger(RemoteCacheInvoker.class);

    private final Map<String, RemoteCacheContext> remoteCaches =  new HashMap<>();


    public void addRemoteCache(String cacheName, RemoteCache remoteCache, MaxIdleTimeLoader maxIdleLoader) {
        RemoteCacheContext ctx = new RemoteCacheContext(remoteCache, maxIdleLoader);
        remoteCaches.put(cacheName, ctx);
    }

    public Set<String> getRemoteCacheNames() {
        return Collections.unmodifiableSet(remoteCaches.keySet());
    }


    public <S extends SessionEntity> void runTask(KeycloakSession kcSession, RealmModel realm, String cacheName, String key, SessionUpdateTask<S> task, SessionEntityWrapper<S> sessionWrapper) {
        RemoteCacheContext context = remoteCaches.get(cacheName);
        if (context == null) {
            return;
        }

        S session = sessionWrapper.getEntity();

        SessionUpdateTask.CacheOperation operation = task.getOperation(session);
        SessionUpdateTask.CrossDCMessageStatus status = task.getCrossDCMessageStatus(sessionWrapper);

        if (status == SessionUpdateTask.CrossDCMessageStatus.NOT_NEEDED) {
            logger.debugf("Skip writing to remoteCache for entity '%s' of cache '%s' and operation '%s'", key, cacheName, operation.toString());
            return;
        }

        long maxIdleTimeMs = context.maxIdleTimeLoader.getMaxIdleTimeMs(realm);

        // Double the timeout to ensure that entry won't expire on remoteCache in case that write of some entities to remoteCache is postponed (eg. userSession.lastSessionRefresh)
        maxIdleTimeMs = maxIdleTimeMs * 2;

        logger.debugf("Running task '%s' on remote cache '%s' . Key is '%s'", operation, cacheName, key);

        runOnRemoteCache(context.remoteCache, maxIdleTimeMs, key, task, session);
    }


    private <S extends SessionEntity> void runOnRemoteCache(RemoteCache remoteCache, long maxIdleMs, String key, SessionUpdateTask<S> task, S session) {
        SessionUpdateTask.CacheOperation operation = task.getOperation(session);

        switch (operation) {
            case REMOVE:
                // REMOVE already handled at remote cache store level
                //remoteCache.remove(key);
                break;
            case ADD:
                remoteCache.put(key, session, task.getLifespanMs(), TimeUnit.MILLISECONDS, maxIdleMs, TimeUnit.MILLISECONDS);
                break;
            case ADD_IF_ABSENT:
                SessionEntity existing = (SessionEntity) remoteCache
                        .withFlags(Flag.FORCE_RETURN_VALUE)
                        .putIfAbsent(key, session, -1, TimeUnit.MILLISECONDS, maxIdleMs, TimeUnit.MILLISECONDS);
                if (existing != null) {
                    throw new IllegalStateException("There is already existing value in cache for key " + key);
                }
                break;
            case REPLACE:
                replace(remoteCache, task.getLifespanMs(), maxIdleMs, key, task);
                break;
            default:
                throw new IllegalStateException("Unsupported state " +  operation);
        }
    }


    private <S extends SessionEntity> void replace(RemoteCache remoteCache, long lifespanMs, long maxIdleMs, String key, SessionUpdateTask<S> task) {
        boolean replaced = false;
        while (!replaced) {
            VersionedValue<S> versioned = remoteCache.getVersioned(key);
            if (versioned == null) {
                logger.warnf("Not found entity to replace for key '%s'", key);
                return;
            }

            S session = versioned.getValue();

            // Run task on the remote session
            task.runUpdate(session);

            if (logger.isDebugEnabled()) {
                logger.debugf("Before replaceWithVersion. Written entity: %s", session.toString());
            }

            replaced = remoteCache.replaceWithVersion(key, session, versioned.getVersion(), lifespanMs, TimeUnit.MILLISECONDS, maxIdleMs, TimeUnit.MILLISECONDS);

            if (!replaced) {
                logger.debugf("Failed to replace entity '%s' . Will retry again", key);
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debugf("Replaced entity in remote cache: %s", session.toString());
                }
            }
        }
    }


    private class RemoteCacheContext {

        private final RemoteCache remoteCache;
        private final MaxIdleTimeLoader maxIdleTimeLoader;

        public RemoteCacheContext(RemoteCache remoteCache, MaxIdleTimeLoader maxIdleLoader) {
            this.remoteCache = remoteCache;
            this.maxIdleTimeLoader = maxIdleLoader;
        }

    }


    @FunctionalInterface
    public interface MaxIdleTimeLoader {

        long getMaxIdleTimeMs(RealmModel realm);

    }


}