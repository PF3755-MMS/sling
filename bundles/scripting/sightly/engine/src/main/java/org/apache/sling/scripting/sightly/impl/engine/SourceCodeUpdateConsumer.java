/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 ******************************************************************************/
package org.apache.sling.scripting.sightly.impl.engine;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.apache.sling.scripting.sightly.impl.engine.compiled.SourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Consumer for the jobs started by the {@link SourceCodeUpdateHandler}.
 *
 * @author <a href="mailto:Stephan.Pirnbaum@googlemail.com">Stephan Pirnbaum</a>
 */
@Component
@Service(value = JobConsumer.class)
@Property(name = JobConsumer.PROPERTY_TOPICS, value = SourceCodeUpdateHandler.JOB_TOPIC)
public class SourceCodeUpdateConsumer implements JobConsumer {

    private final Logger LOG = LoggerFactory.getLogger(SourceCodeUpdateConsumer.class);

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private SightlyJavaCompilerService sightlyJavaCompilerService;

    @Reference
    private SightlyEngineConfiguration sightlyEngineConfiguration;

    @Override
    public JobResult process(Job job) {
        ResourceResolver adminResolver = null;
        try {
            adminResolver = resourceResolverFactory.getAdministrativeResourceResolver(null);
            final String resourcePath = (String) job.getProperty("resourcePath");
            final Resource resource = adminResolver.getResource(resourcePath);
            final String fqcn = resourcePath.replaceFirst("/", "").replaceAll("/", ".").replaceAll("-", "_").substring(0, resourcePath.length() - 1 - ".java".length());
            if (resource != null && resource.isResourceType("nt:file")) {
                this.sightlyJavaCompilerService.getUseObjectAndRecompileIfNeeded(
                        SourceIdentifier.getPOJOFromFQCN(adminResolver, sightlyEngineConfiguration.getBundleSymbolicName(), fqcn), true);
            }
            return JobResult.OK;
        } catch (LoginException e) {
            LOG.error("Exception: " + e, e);
            return JobResult.FAILED;
        } catch (IllegalAccessException | InstantiationException | ClassNotFoundException | IOException e) {
            LOG.error("Not able to compile or instantiate class:\n" + e, e);
            return JobResult.FAILED;
        } finally {
            if (adminResolver != null) {
                adminResolver.close();
            }
        }
    }
}
