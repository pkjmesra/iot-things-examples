/*
 *                                            Bosch SI Example Code License
 *                                              Version 1.0, January 2016
 *
 * Copyright 2017 Bosch Software Innovations GmbH ("Bosch SI"). All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * BOSCH SI PROVIDES THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE ENTIRE RISK AS TO
 * THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU. SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF
 * ALL NECESSARY SERVICING, REPAIR OR CORRECTION. THIS SHALL NOT APPLY TO MATERIAL DEFECTS AND DEFECTS OF TITLE WHICH
 * BOSCH SI HAS FRAUDULENTLY CONCEALED. APART FROM THE CASES STIPULATED ABOVE, BOSCH SI SHALL BE LIABLE WITHOUT
 * LIMITATION FOR INTENT OR GROSS NEGLIGENCE, FOR INJURIES TO LIFE, BODY OR HEALTH AND ACCORDING TO THE PROVISIONS OF
 * THE GERMAN PRODUCT LIABILITY ACT (PRODUKTHAFTUNGSGESETZ). THE SCOPE OF A GUARANTEE GRANTED BY BOSCH SI SHALL REMAIN
 * UNAFFECTED BY LIMITATIONS OF LIABILITY. IN ALL OTHER CASES, LIABILITY OF BOSCH SI IS EXCLUDED. THESE LIMITATIONS OF
 * LIABILITY ALSO APPLY IN REGARD TO THE FAULT OF VICARIOUS AGENTS OF BOSCH SI AND THE PERSONAL LIABILITY OF BOSCH SI'S
 * EMPLOYEES, REPRESENTATIVES AND ORGANS.
 */
package com.bosch.iot.things.example.historian;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.PostConstruct;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.WriteResultChecking;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.mongodb.BasicDBObject;

import com.bosch.cr.integration.IntegrationClient;
import com.bosch.cr.integration.client.ThingsClientFactory;
import com.bosch.cr.integration.client.configuration.ProviderConfiguration;
import com.bosch.cr.integration.client.configuration.ProxyConfiguration;
import com.bosch.cr.integration.client.configuration.PublicKeyAuthenticationConfiguration;
import com.bosch.cr.integration.client.configuration.TwinConfiguration;
import com.bosch.cr.integration.client.messaging.internal.thingsws.ThingsWsMessagingProviderConfigurationImpl;
import com.bosch.cr.integration.things.ChangeAction;
import com.bosch.cr.integration.things.FeatureChange;
import com.bosch.cr.json.JsonArray;
import com.bosch.cr.json.JsonFactory;
import com.bosch.cr.json.JsonObject;
import com.bosch.cr.json.JsonPointer;
import com.bosch.cr.json.JsonValue;
import com.bosch.cr.model.things.Feature;
import com.bosch.cr.model.things.Thing;

/**
 * Example implemenetation of a history collector. It registers as a consumer for all changes of features of Things and
 * stores them on level of individual properties in the MongoDB.
 */
@Component
public class Collector implements Runnable {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(Collector.class);

    /**
     * Backlog of change values for each property
     */
    private static final int DEFAULT_HISTORY_SIZE = 1000;
    private static final int MAX_HISTORY_SIZE = 100000;
    
    @Autowired
    private MongoTemplate mongoTemplate;

    private final Map<String,Feature> historianConfigCache = Collections.synchronizedMap(new Cache<>(1000));

    private static class History {

        private final String thingId;
        private final String featureId;
        private final JsonPointer path;
        private final JsonValue value;
        private final LocalDateTime timestamp;

        public History(String thingId, String featureId, JsonPointer path, JsonValue value, LocalDateTime timestamp) {
            this.thingId = thingId;
            this.featureId = featureId;
            this.path = path;
            this.value = value;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "History{" + "thingId=" + thingId + ", featureId=" + featureId + ", path=" + path + ", value=" +
                    value + ", timestamp=" + timestamp + '}';
        }

    }

    @PostConstruct
    public void start() {
        mongoTemplate.setWriteResultChecking(WriteResultChecking.EXCEPTION);

        if (!mongoTemplate.collectionExists("history")) {
            mongoTemplate.createCollection("history");
        }

        Thread thread = new Thread(this);
        thread.start();

        Executors.newScheduledThreadPool(1).schedule(historianConfigCache::clear, 5, TimeUnit.MINUTES);

        LOGGER.info("Historian collector started");
    }

    @Override
    public void run() {
        IntegrationClient client = setupClient();

        client.twin().registerForFeatureChanges("changes", change -> {
            final ChangeAction action = change.getAction();
            if (action == ChangeAction.CREATED || action == ChangeAction.UPDATED) {
                LOGGER.debug("Change: {}", change);

                final int historySize = getHistorySize(client, change);

                // collect list of individual property changes
                List<History> target = new LinkedList<>();
                collectChanges(target, change.getThingId(), change.getFeature().getId(),
                        JsonPointer.newInstance(), change.getValue().get());

                // write them all the the MongoDB
                target.stream().forEachOrdered(h -> storeHistory(h, historySize));
            }
        });

        // start consuming changes
        client.twin().startConsumption();
    }

    /** Get history size limit (either default or configured by feature). */
    private int getHistorySize(final IntegrationClient client, final FeatureChange change) {
        Optional<Feature> historianConfig;
        if (historianConfigCache.containsKey(change.getThingId())) {
            historianConfig = Optional.ofNullable(historianConfigCache.get(change.getThingId()));
        } else  {
            try {
                historianConfig =
                        Optional.of(client.twin().forId(change.getThingId())
                        .forFeature("HistorianConfig").retrieve().get(5, TimeUnit.SECONDS));
                LOGGER.debug("Retrieved HistorianConfig for thing {}: {}", change.getThingId(), historianConfig);
                historianConfigCache.put(change.getThingId(), historianConfig.get());
            }
            catch (ExecutionException e) {
                LOGGER.debug("HistorianConfig for thing {} not found or errors during retrieve: {}", change.getThingId(), e.getMessage());
                historianConfig = Optional.empty();
            }
            catch (TimeoutException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        final int historySize;
        if (!historianConfig.isPresent()) {
            historySize = DEFAULT_HISTORY_SIZE;
        } else {
            historySize  = Math.min(MAX_HISTORY_SIZE, 
                historianConfig.get().getProperty(JsonFactory.newPointer("historySize"))
                    .orElse(JsonFactory.newValue(DEFAULT_HISTORY_SIZE)).asInt());
        }
        return historySize;
    }

    /**
     * Write history to the the MongoDB
     */
    private void storeHistory(History h, int historySize) {
        LOGGER.trace("Store history (max {}): {}", historySize, h);

        // do combined update query: add newest value+timestamp to the array property and slice array if too long
        String id = h.thingId + "/features/" + h.featureId + h.path;
        Update update = new Update()
                .push("values",
                        new BasicDBObject("$each", Arrays.asList(getJavaValue(h.value)))
                                .append("$slice", -historySize))
                .push("timestamps",
                        new BasicDBObject("$each", Arrays.asList(h.timestamp))
                                .append("$slice", -historySize));

        // update or create document for this specific property in this thing/feature
        mongoTemplate.upsert(
                Query.query(Criteria.where("_id").is(id)),
                update, String.class, "history");
    }

    /**
     * Collect list of individual property changes
     */
    private static void collectChanges(List target, String thingId, String featureId, JsonPointer path,
            JsonValue value) {
        if (value.isObject()) {
            // on Object recursively collect all individual properties with concatenated property path
            JsonObject obj = value.asObject();
            obj.forEach(c -> {
                collectChanges(target, thingId, featureId, path.addLeaf(c.getKey()), c.getValue());
            });
        } else {
            target.add(new History(thingId, featureId, path, value, LocalDateTime.now()));
        }
    }

    /**
     * Return Java representation of JsonValue.
     * <p>
     * For primitive types these are objects of type Integer, Long, Double, Boolean or String. Arrays are returned as
     * Object[] and JsonObjects as Map.
     */
    private static Object getJavaValue(JsonValue v) {
        if (v.isNull()) {
            return null;
        } else if (v.isNumber()) {
            try {
                return v.asInt();
            } catch (NumberFormatException ex1) {
                try {
                    return v.asLong();
                } catch (NumberFormatException ex2) {
                    return v.asDouble();
                }
            }
        } else if (v.isBoolean()) {
            return v.asBoolean();
        } else if (v.isString()) {
            return v.asString();
        } else if (v.isArray()) {
            JsonArray a = v.asArray();
            return a.stream().map(w -> getJavaValue(w)).toArray();
        } else if (v.isObject()) {
            JsonObject o = v.asObject();
            Map<String, Object> m = new HashMap<>();
            o.forEach(e -> m.put(e.getKeyName(), getJavaValue(e.getValue())));
            return m;
        } else {
            // fallback: render as String
            return v.toString();
        }
    }

    private static IntegrationClient setupClient() throws RuntimeException, NumberFormatException {
        Properties props = new Properties(System.getProperties());
        try {
            if (new File("config.properties").exists()) {
                props.load(new FileReader("config.properties"));
            } else {
                InputStream i = Thread.currentThread().getContextClassLoader().getResourceAsStream("config.properties");
                props.load(i);
                i.close();
            }
            LOGGER.info("Used config: {}", props);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        String thingsMessagingEndpointUrl = props.getProperty("thingsMessagingEndpointUrl");
        String clientId = props.getProperty("clientId");
        String apiToken = props.getProperty("apiToken");
        String defaultNamespace = props.getProperty("defaultNamespace");
        URI keystoreUri;
        try {
            keystoreUri = Collector.class.getResource("/CRClient.jks").toURI();
        } catch (URISyntaxException ex) {
            throw new RuntimeException(ex);
        }

        String keystorePassword = props.getProperty("keyStorePassword");
        String keyAlias = props.getProperty("keyAlias");
        String keyAliasPassword = props.getProperty("keyAliasPassword");
        String proxyHost = props.getProperty("http.proxyHost");
        String proxyPort = props.getProperty("http.proxyPort");

        PublicKeyAuthenticationConfiguration authenticationConfiguration;
        try {
            authenticationConfiguration = PublicKeyAuthenticationConfiguration.newBuilder()
                    .clientId(clientId)
                    .keyStoreLocation(keystoreUri.toURL())
                    .keyStorePassword(keystorePassword)
                    .alias(keyAlias)
                    .aliasPassword(keyAliasPassword)
                    .build();
        } catch (MalformedURLException ex) {
            throw new RuntimeException(ex);
        }

        ProviderConfiguration providerConfig = ThingsWsMessagingProviderConfigurationImpl.newBuilder()
                .endpoint(thingsMessagingEndpointUrl)
                .authenticationConfiguration(authenticationConfiguration)
                .build();

        TwinConfiguration.OptionalTwinConfigurationStep configSettable = TwinConfiguration.newBuilder()
                .apiToken(apiToken)
                .defaultNamespace(defaultNamespace)
                .providerConfiguration(providerConfig);

        if (proxyHost != null && proxyPort != null) {
            configSettable = configSettable.proxyConfiguration(
                    ProxyConfiguration.newBuilder()
                            .proxyHost(proxyHost)
                            .proxyPort(Integer.parseInt(proxyPort))
                            .build());
        }

        IntegrationClient client = ThingsClientFactory.newInstance(configSettable.build());

        return client;
    }

}
