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
import org.apache.sling.api.SlingConstants;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Handler for updated java files in the /apps directory.
 *
 * @author <a href="mailto:Stephan.Pirnbaum@googlemail.com">Stephan Pirnbaum</a>
 */
@Component(immediate = true)
@Service(value = EventHandler.class)
@Property(name = EventConstants.EVENT_TOPIC,
        value = {SlingConstants.TOPIC_RESOURCE_ADDED, SlingConstants.TOPIC_RESOURCE_CHANGED, SlingConstants.TOPIC_RESOURCE_REMOVED})
public class SourceCodeUpdateHandler implements EventHandler {

    // Default logger
    private static final Logger LOG = LoggerFactory.getLogger(SourceCodeUpdateHandler.class);

    // The job manager for starting the jobs
    @Reference
    private JobManager jobManager;

    public static final String JOB_TOPIC = "com/sling/eventing/SourceCodeUpdateEvent";

    @Override
    public void handleEvent(Event event) {
        final String propertyPath = (String) event.getProperty(SlingConstants.PROPERTY_PATH);
        final String propertyResourceType = (String) event.getProperty(SlingConstants.PROPERTY_RESOURCE_TYPE);
        if(propertyPath.startsWith("/apps") && "nt:file".equals(propertyResourceType) && propertyPath.endsWith("java")) {
            final Map<String, Object> payload = new HashMap<String, Object>();
            payload.put("resourcePath", propertyPath);
            this.jobManager.addJob(JOB_TOPIC, payload);
            LOG.info("The update job has been started for: {}", propertyPath);
        }
    }
}
