/*
 *  Copyright (c) 2017 Otávio Santana and others
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   and Apache License v2.0 which accompanies this distribution.
 *   The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 *   and the Apache License v2.0 is available at http://www.opensource.org/licenses/apache2.0.php.
 *
 *   You may elect to redistribute this code under either of these licenses.
 *
 *   Contributors:
 *
 *   Otavio Santana
 */
package org.jnosql.diana.elasticsearch.document;


import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.jnosql.diana.api.Settings;
import org.jnosql.diana.api.document.UnaryDocumentConfiguration;
import org.jnosql.diana.driver.ConfigurationReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * The implementation of {@link UnaryDocumentConfiguration} that returns {@link ElasticsearchDocumentCollectionManagerFactory}.
 * It tries to read the configuration properties from diana-elasticsearch.properties file. To get some information:
 * <p>elasticsearch-host-n: the host to client connection, if necessary to define a different port than default just
 * use colon, ':' eg: elasticsearch-host-1=172.17.0.2:1234</p>
 */
public class ElasticsearchDocumentConfiguration implements UnaryDocumentConfiguration<ElasticsearchDocumentCollectionManagerFactory> {

    private static final String FILE_CONFIGURATION = "diana-elasticsearch.properties";
    private static final String HOST_PREFIX = "elasticsearch-host-";
    private static final int DEFAULT_PORT = 9200;

    private List<HttpHost> httpHosts = new ArrayList<>();


    public ElasticsearchDocumentConfiguration() {

        Map<String, String> configurations = ConfigurationReader.from(FILE_CONFIGURATION);

        if (configurations.isEmpty()) {
            return;
        }
        configurations.keySet().stream()
                .filter(k -> k.startsWith(HOST_PREFIX))
                .sorted()
                .map(h -> ElasticsearchAddress.of(configurations.get(h), DEFAULT_PORT))
                .map(ElasticsearchAddress::toTransportAddress)
                .forEach(httpHosts::add);
    }




    @Override
    public ElasticsearchDocumentCollectionManagerFactory get() throws UnsupportedOperationException {
        return get(Settings.builder().build());
    }

    @Override
    public ElasticsearchDocumentCollectionManagerFactory get(Settings settings) throws NullPointerException {
        requireNonNull(settings, "settings is required");

        Map<String, String> configurations = new HashMap<>();
        settings.forEach((key, value) -> configurations.put(key, value.toString()));

        configurations.keySet().stream()
                .filter(k -> k.startsWith(HOST_PREFIX))
                .sorted()
                .map(h -> ElasticsearchAddress.of(configurations.get(h), DEFAULT_PORT))
                .map(ElasticsearchAddress::toTransportAddress)
                .forEach(httpHosts::add);

        RestClientBuilder builder = RestClient.builder(httpHosts.toArray(new HttpHost[httpHosts.size()]));
        RestHighLevelClient client = new RestHighLevelClient(builder);
        return new ElasticsearchDocumentCollectionManagerFactory(client);
    }


    @Override
    public ElasticsearchDocumentCollectionManagerFactory getAsync() throws UnsupportedOperationException {
        return get();
    }

    @Override
    public ElasticsearchDocumentCollectionManagerFactory getAsync(org.jnosql.diana.api.Settings settings) throws NullPointerException {
        return get(settings);
    }






}
