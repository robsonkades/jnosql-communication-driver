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

package org.eclipse.jnosql.diana.cassandra.column;


import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.codec.CodecNotFoundException;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import com.datastax.oss.driver.api.querybuilder.QueryBuilder;
import com.datastax.oss.driver.api.querybuilder.insert.Insert;
import com.datastax.oss.driver.api.querybuilder.insert.InsertInto;
import com.datastax.oss.driver.api.querybuilder.insert.RegularInsert;
import com.datastax.oss.driver.api.querybuilder.select.Select;
import com.datastax.oss.driver.api.querybuilder.term.Term;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import jakarta.nosql.CommunicationException;
import jakarta.nosql.Sort;
import jakarta.nosql.SortType;
import jakarta.nosql.Value;
import jakarta.nosql.column.Column;
import jakarta.nosql.column.ColumnEntity;
import jakarta.nosql.column.ColumnQuery;
import org.eclipse.jnosql.diana.driver.ValueUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

final class QueryUtils {


    private QueryUtils() {
    }


    static Insert insert(ColumnEntity entity, String keyspace, CqlSession session, Duration duration) {

        Map<String, Term> values = new HashMap<>();
        InsertInto insert = QueryBuilder.insertInto(keyspace, entity.getName());
        entity.getColumns().stream()
                .forEach(c -> {
                    if (UDT.class.isInstance(c)) {
                        insertUDT(UDT.class.cast(c), keyspace, entity.getName(), session, values);
                    } else {
                        insertSingleField(c, values);
                    }
                });

        RegularInsert regularInsert = insert.values(values);
        if (duration != null) {
            return regularInsert.usingTtl((int) duration.getSeconds());
        }
        return regularInsert;
    }

    public static Select select(ColumnQuery query, String keyspace) {
        String columnFamily = query.getColumnFamily();
        final List<String> columns = query.getColumns();

        Select select = null;
        if (columns.isEmpty()) {
            select = QueryBuilder.selectFrom(keyspace, columnFamily).all();
        } else {
            select = QueryBuilder.selectFrom(keyspace, columnFamily).columns(columns);
        }

        select = select.where(Relations.createClause(query.getCondition().orElse(null)));
        final Map<String, ClusteringOrder> sort = query.getSorts().stream().collect(Collectors.toMap(s -> s.getName(), mapSort()));
        select = select.orderBy(sort);
        return select;
    }

    private static Function<Sort, ClusteringOrder> mapSort() {
        return s -> SortType.ASC.equals(s.getType()) ? ClusteringOrder.ASC :
                ClusteringOrder.DESC;
    }

    private static void insertUDT(UDT udt, String keyspace, String columnFamily, CqlSession session,
                                  Map<String, Term> values) {

        final Optional<KeyspaceMetadata> keyspaceMetadata = session.getMetadata().getKeyspace(keyspace);
        UserDefinedType userType = keyspaceMetadata
                .flatMap(ks -> ks.getUserDefinedType(udt.getUserType()))
                .orElseThrow(() -> new IllegalArgumentException("Missing UDT definition"));

        final TableMetadata tableMetadata = keyspaceMetadata
                .flatMap(k -> k.getTable(columnFamily))
                .orElseThrow(() -> new IllegalArgumentException("Missing Table definition"));

        final ColumnMetadata columnMetadata = tableMetadata.getColumn(getName(udt))
                .orElseThrow(() -> new IllegalArgumentException("Missing the column definition"));

        final DataType type = columnMetadata.getType();
        Iterable elements = Iterable.class.cast(udt.get());
        Object udtValue = getUdtValue(userType, elements, type);
        values.put(getName(udt), QueryBuilder.literal(udtValue));
    }

    private static Object getUdtValue(UserDefinedType userType, Iterable elements, DataType type) {

        Collection<Object> udtValues = getCollectionUdt(type);

        UdtValue udtValue = userType.newValue();
        final List<String> udtNames = userType.getFieldNames().stream().map(CqlIdentifier::asInternal)
                .collect(Collectors.toList());
        for (Object object : elements) {
            if (Column.class.isInstance(object)) {
                Column column = Column.class.cast(object);
                Object convert = ValueUtil.convert(column.getValue());

                final int index = udtNames.indexOf(column.getName());
                if (index < 0) {
                    throw new CommunicationException("There is not the field: " + column.getName() +
                            " the fields available are " + udtNames + " in the UDT type " + userType.getName()
                            .asCql(true));
                }
                DataType fieldType = userType.getFieldTypes().get(index);
                TypeCodec<Object> objectTypeCodec = CodecRegistry.DEFAULT.codecFor(fieldType);
                udtValue.set(getName(column), convert, objectTypeCodec);

            } else if (Iterable.class.isInstance(object)) {
                udtValues.add(getUdtValue(userType, Iterable.class.cast(Iterable.class.cast(object)), type));
            }
        }
        if (udtValues.isEmpty()) {
            return udtValue;
        }
        return udtValues;

    }

    private static Collection<Object> getCollectionUdt(DataType type) {
        if (ProtocolConstants.DataType.SET == type.getProtocolCode()) {
            return new HashSet<>();
        } else {
            return new ArrayList<>();
        }
    }

    private static void insertSingleField(Column column, Map<String, Term> values) {
        Object value = column.get();
        try {
            CodecRegistry.DEFAULT.codecFor(value);
            values.put(getName(column), QueryBuilder.literal(value));
        } catch (CodecNotFoundException exp) {
            values.put(getName(column), QueryBuilder.literal(ValueUtil.convert(column.getValue())));
        }
    }


    public static String count(String columnFamily, String keyspace) {
        return String.format("select count(*) from %s.%s", keyspace, columnFamily);
    }

    static String getName(Column column) {
        return getName(column.getName());
    }

    static String getName(String name) {
        if (name.charAt(0) == '_') {
            return "\"" + name + "\"";
        }
        return name;
    }

    private static Object[] getIinValue(Value value) {
        return ValueUtil.convertToList(value).toArray();
    }


}
