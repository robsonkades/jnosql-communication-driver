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

package org.eclipse.jnosql.communication.mongodb.document;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;
import jakarta.nosql.Sort;
import jakarta.nosql.SortType;
import jakarta.nosql.document.DocumentCollectionManager;
import jakarta.nosql.document.DocumentDeleteQuery;
import jakarta.nosql.document.DocumentEntity;
import jakarta.nosql.document.DocumentQuery;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.jnosql.communication.document.Documents;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.eclipse.jnosql.communication.mongodb.document.MongoDBUtils.ID_FIELD;
import static org.eclipse.jnosql.communication.mongodb.document.MongoDBUtils.getDocument;

/**
 * The mongodb implementation to {@link DocumentCollectionManager} that does not support TTL methods
 * <p>{@link MongoDBDocumentCollectionManager#insert(DocumentEntity, Duration)}</p>
 */
public class MongoDBDocumentCollectionManager implements DocumentCollectionManager {

    private static final BsonDocument EMPTY = new BsonDocument();

    private final MongoDatabase mongoDatabase;


    MongoDBDocumentCollectionManager(MongoDatabase mongoDatabase) {
        this.mongoDatabase = mongoDatabase;
    }


    @Override
    public DocumentEntity insert(DocumentEntity entity) {
        Objects.requireNonNull(entity, "entity is required");
        String collectionName = entity.getName();
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        Document document = getDocument(entity);
        collection.insertOne(document);
        boolean hasNotId = entity.getDocuments().stream()
                .map(jakarta.nosql.document.Document::getName).noneMatch(k -> k.equals(ID_FIELD));
        if (hasNotId) {
            entity.add(Documents.of(ID_FIELD, document.get(ID_FIELD)));
        }
        return entity;
    }


    @Override
    public DocumentEntity insert(DocumentEntity entity, Duration ttl) {
        throw new UnsupportedOperationException("MongoDB does not support save with TTL");
    }

    @Override
    public Iterable<DocumentEntity> insert(Iterable<DocumentEntity> entities) {
        Objects.requireNonNull(entities, "entities is required");
        return StreamSupport.stream(entities.spliterator(), false)
                .map(this::insert)
                .collect(toList());
    }

    @Override
    public Iterable<DocumentEntity> insert(Iterable<DocumentEntity> entities, Duration ttl) {
        Objects.requireNonNull(entities, "entities is required");
        Objects.requireNonNull(ttl, "ttl is required");
        return StreamSupport.stream(entities.spliterator(), false)
                .map(e -> insert(e, ttl))
                .collect(toList());
    }


    @Override
    public DocumentEntity update(DocumentEntity entity) {
        Objects.requireNonNull(entity, "entity is required");

        DocumentEntity copy = entity.copy();
        String collectionName = entity.getName();
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        Document id = copy.find(ID_FIELD)
                .map(d -> new Document(d.getName(), d.getValue().get()))
                .orElseThrow(() -> new UnsupportedOperationException("To update this DocumentEntity " +
                        "the field `id` is required"));
        copy.remove(ID_FIELD);
        collection.findOneAndReplace(id, getDocument(entity));
        return entity;
    }

    @Override
    public Iterable<DocumentEntity> update(Iterable<DocumentEntity> entities) {
        Objects.requireNonNull(entities, "entities is required");
        return StreamSupport.stream(entities.spliterator(), false)
                .map(this::update)
                .collect(toList());
    }


    @Override
    public void delete(DocumentDeleteQuery query) {
        Objects.requireNonNull(query, "query is required");

        String collectionName = query.getDocumentCollection();
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        Bson mongoDBQuery = query.getCondition().map(DocumentQueryConversor::convert).orElse(EMPTY);
        collection.deleteMany(mongoDBQuery);
    }


    @Override
    public Stream<DocumentEntity> select(DocumentQuery query) {
        Objects.requireNonNull(query, "query is required");
        String collectionName = query.getDocumentCollection();
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        Bson mongoDBQuery = query.getCondition().map(DocumentQueryConversor::convert).orElse(EMPTY);

        FindIterable<Document> documents = collection.find(mongoDBQuery);
        documents.projection(Projections.include(query.getDocuments()));
        if (query.getSkip() > 0) {
            documents.skip((int) query.getSkip());
        }

        if (query.getLimit() > 0) {
            documents.limit((int) query.getLimit());
        }

        query.getSorts().stream().map(this::getSort).forEach(documents::sort);

        return stream(documents.spliterator(), false).map(MongoDBUtils::of)
                .map(ds -> DocumentEntity.of(collectionName, ds));

    }

    /**
     * Removes all documents from the collection that match the given query filter.
     * If no documents match, the collection is not modified.
     *
     * @param collectionName the collection name
     * @param filter         the delete filter
     * @return the number of documents deleted.
     * @throws NullPointerException when filter or collectionName is null
     */
    public long delete(String collectionName, Bson filter) {
        Objects.requireNonNull(filter, "filter is required");
        Objects.requireNonNull(collectionName, "collectionName is required");

        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        DeleteResult result = collection.deleteMany(filter);
        return result.getDeletedCount();
    }

    /**
     * Aggregates documents according to the specified aggregation pipeline.
     *
     * @param collectionName the collection name
     * @param pipeline the aggregation pipeline
     * @return the number of documents deleted.
     * @throws NullPointerException when filter or collectionName is null
     */
    public Stream<Map<String, BsonValue>> aggregate(String collectionName, List<Bson> pipeline) {
        Objects.requireNonNull(pipeline, "filter is required");
        Objects.requireNonNull(collectionName, "collectionName is required");
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        AggregateIterable<Document> aggregate = collection.aggregate(pipeline);
        return stream(aggregate.spliterator(), false)
                .map(Document::toBsonDocument);
    }

    /**
     * Finds all documents in the collection.
     *
     * @param collectionName the collection name
     * @param filter         the query filter
     * @return the stream result
     * @throws NullPointerException when filter or collectionName is null
     */
    public Stream<DocumentEntity> select(String collectionName, Bson filter) {
        Objects.requireNonNull(filter, "filter is required");
        Objects.requireNonNull(collectionName, "collectionName is required");
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        FindIterable<Document> documents = collection.find(filter);
        return stream(documents.spliterator(), false).map(MongoDBUtils::of)
                .map(ds -> DocumentEntity.of(collectionName, ds));
    }


    @Override
    public long count(String documentCollection) {
        Objects.requireNonNull(documentCollection, "documentCollection is required");
        MongoCollection<Document> collection = mongoDatabase.getCollection(documentCollection);
        return collection.countDocuments();
    }

    private Bson getSort(Sort sort) {
        boolean isAscending = SortType.ASC.equals(sort.getType());
        return isAscending ? Sorts.ascending(sort.getName()) : Sorts.descending(sort.getName());
    }

    @Override
    public void close() {

    }


}
