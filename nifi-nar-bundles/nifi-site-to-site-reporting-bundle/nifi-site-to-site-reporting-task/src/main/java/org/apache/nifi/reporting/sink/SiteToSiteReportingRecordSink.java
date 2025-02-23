/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.reporting.sink;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.events.EventReporter;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.record.sink.RecordSinkService;
import org.apache.nifi.remote.Transaction;
import org.apache.nifi.remote.TransferDirection;
import org.apache.nifi.remote.client.SiteToSiteClient;
import org.apache.nifi.remote.protocol.SiteToSiteTransportProtocol;
import org.apache.nifi.remote.protocol.http.HttpProxy;
import org.apache.nifi.remote.util.SiteToSiteRestApiClient;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.reporting.s2s.SiteToSiteUtils;
import org.apache.nifi.serialization.RecordSetWriter;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Tags({ "db", "s2s", "site", "record"})
@CapabilityDescription("Provides a service to write records using a configured RecordSetWriter over a Site-to-Site connection.")
public class SiteToSiteReportingRecordSink extends AbstractControllerService implements RecordSinkService {

    private List<PropertyDescriptor> properties;
    private volatile SiteToSiteClient siteToSiteClient;
    private volatile RecordSetWriterFactory writerFactory;
    private volatile StateManager stateManager;

    @Override
    protected void init(final ControllerServiceInitializationContext context) {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(RECORD_WRITER_FACTORY);
        properties.add(SiteToSiteUtils.DESTINATION_URL);
        properties.add(SiteToSiteUtils.PORT_NAME);
        properties.add(SiteToSiteUtils.SSL_CONTEXT);
        properties.add(SiteToSiteUtils.INSTANCE_URL);
        properties.add(SiteToSiteUtils.COMPRESS);
        properties.add(SiteToSiteUtils.TIMEOUT);
        properties.add(SiteToSiteUtils.BATCH_SIZE);
        properties.add(SiteToSiteUtils.TRANSPORT_PROTOCOL);
        properties.add(SiteToSiteUtils.HTTP_PROXY_HOSTNAME);
        properties.add(SiteToSiteUtils.HTTP_PROXY_PORT);
        properties.add(SiteToSiteUtils.HTTP_PROXY_USERNAME);
        properties.add(SiteToSiteUtils.HTTP_PROXY_PASSWORD);
        this.properties = Collections.unmodifiableList(properties);
        this.stateManager = context.getStateManager();
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws InitializationException {
        try {
            final SSLContextService sslContextService = context.getProperty(SiteToSiteUtils.SSL_CONTEXT).asControllerService(SSLContextService.class);
            final SSLContext sslContext = sslContextService == null ? null : sslContextService.createSSLContext(SSLContextService.ClientAuth.REQUIRED);
            final ComponentLog logger = getLogger();
            final EventReporter eventReporter = (EventReporter) (severity, category, message) -> {
                switch (severity) {
                    case WARNING:
                        logger.warn(message);
                        break;
                    case ERROR:
                        logger.error(message);
                        break;
                    default:
                        break;
                }
            };

            final String destinationUrl = context.getProperty(SiteToSiteUtils.DESTINATION_URL).evaluateAttributeExpressions().getValue();

            final SiteToSiteTransportProtocol mode = SiteToSiteTransportProtocol.valueOf(context.getProperty(SiteToSiteUtils.TRANSPORT_PROTOCOL).getValue());
            final HttpProxy httpProxy = mode.equals(SiteToSiteTransportProtocol.RAW) || StringUtils.isEmpty(context.getProperty(SiteToSiteUtils.HTTP_PROXY_HOSTNAME).getValue()) ? null
                    : new HttpProxy(context.getProperty(SiteToSiteUtils.HTTP_PROXY_HOSTNAME).getValue(), context.getProperty(SiteToSiteUtils.HTTP_PROXY_PORT).asInteger(),
                    context.getProperty(SiteToSiteUtils.HTTP_PROXY_USERNAME).getValue(), context.getProperty(SiteToSiteUtils.HTTP_PROXY_PASSWORD).getValue());

            siteToSiteClient = new SiteToSiteClient.Builder()
                    .urls(SiteToSiteRestApiClient.parseClusterUrls(destinationUrl))
                    .portName(context.getProperty(SiteToSiteUtils.PORT_NAME).getValue())
                    .useCompression(context.getProperty(SiteToSiteUtils.COMPRESS).asBoolean())
                    .eventReporter(eventReporter)
                    .sslContext(sslContext)
                    .stateManager(stateManager)
                    .timeout(context.getProperty(SiteToSiteUtils.TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
                    .transportProtocol(mode)
                    .httpProxy(httpProxy)
                    .build();

            writerFactory = context.getProperty(RECORD_WRITER_FACTORY).asControllerService(RecordSetWriterFactory.class);
        } catch(Exception e) {
            throw new InitializationException(e);
        }
    }

    @Override
    public WriteResult sendData(final RecordSet recordSet, final Map<String,String> attributes, final boolean sendZeroResults) throws IOException {

        try {
            WriteResult writeResult = null;
            final Transaction transaction = getClient().createTransaction(TransferDirection.SEND);
            if (transaction == null) {
                getLogger().info("All destination nodes are penalized; will attempt to send data later");
            } else {
                final RecordSchema writeSchema = getWriterFactory().getSchema(null, recordSet.getSchema());
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                int recordCount = 0;
                try (final RecordSetWriter writer = getWriterFactory().createWriter(getLogger(), writeSchema, out, attributes)) {
                    writer.beginRecordSet();
                    Record record;
                    while ((record = recordSet.next()) != null) {
                        writer.write(record);
                    }
                    writeResult = writer.finishRecordSet();
                    recordCount = writeResult.getRecordCount();

                    attributes.put(CoreAttributes.MIME_TYPE.key(), writer.getMimeType());
                    attributes.put("record.count", Integer.toString(recordCount));
                    attributes.putAll(writeResult.getAttributes());
                }

                if (recordCount > 0 || sendZeroResults) {
                    transaction.send(out.toByteArray(), attributes);
                }
                transaction.confirm();
                transaction.complete();
            }
            return writeResult;
        } catch(IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("Failed to write metrics using record writer: " + e.getMessage(), e);
        }

    }

    @OnDisabled
    public void stop() throws IOException {
        final SiteToSiteClient client = getClient();
        if (client != null) {
            client.close();
        }
    }

    // this getter is intended explicitly for testing purposes
    protected SiteToSiteClient getClient() {
        return this.siteToSiteClient;
    }

    // this getter is intended explicitly for testing purposes
    protected RecordSetWriterFactory getWriterFactory() {
        return this.writerFactory;
    }
}
