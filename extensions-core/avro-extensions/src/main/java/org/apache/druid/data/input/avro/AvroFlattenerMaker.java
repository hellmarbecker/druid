/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.data.input.avro;

import com.google.common.collect.Lists;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JsonProvider;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericArray;
import org.apache.avro.generic.GenericEnumSymbol;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.parsers.NotImplementedMappingProvider;
import org.apache.druid.java.util.common.parsers.ObjectFlatteners;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AvroFlattenerMaker implements ObjectFlatteners.FlattenerMaker<GenericRecord>
{
  private final JsonProvider avroJsonProvider;
  private final Configuration jsonPathConfiguration;

  private static final EnumSet<Schema.Type> ROOT_TYPES = EnumSet.of(
      Schema.Type.STRING,
      Schema.Type.BYTES,
      Schema.Type.INT,
      Schema.Type.LONG,
      Schema.Type.FLOAT,
      Schema.Type.DOUBLE,
      Schema.Type.ENUM,
      Schema.Type.FIXED
  );

  private static boolean isPrimitive(Schema schema)
  {
    return ROOT_TYPES.contains(schema.getType());
  }

  private static boolean isPrimitiveArray(Schema schema)
  {
    return schema.getType().equals(Schema.Type.ARRAY) && isPrimitive(schema.getElementType());
  }

  private static boolean isOptionalPrimitive(Schema schema)
  {
    return schema.getType().equals(Schema.Type.UNION) &&
           schema.getTypes().size() == 2 &&
           (
               (schema.getTypes().get(0).getType().equals(Schema.Type.NULL) &&
                (isPrimitive(schema.getTypes().get(1)) || isPrimitiveArray(schema.getTypes().get(1)))) ||
               (schema.getTypes().get(1).getType().equals(Schema.Type.NULL) &&
                (isPrimitive(schema.getTypes().get(0)) || isPrimitiveArray(schema.getTypes().get(0))))
           );
  }

  private static boolean isFieldPrimitive(Schema.Field field)
  {
    return isPrimitive(field.schema()) ||
           isPrimitiveArray(field.schema()) ||
           isOptionalPrimitive(field.schema());
  }

  private final boolean fromPigAvroStorage;
  private final boolean binaryAsString;

  private final boolean discoverNestedFields;

  /**
   * @param fromPigAvroStorage    boolean to specify the data file is stored using AvroStorage
   * @param binaryAsString        if true, treat byte[] as utf8 encoded values and coerce to strings, else leave as byte[]
   * @param extractUnionsByType   if true, unions will be extracted to separate nested fields for each type. See
   *                              {@link GenericAvroJsonProvider#extractUnionTypes(Object)} for more details
   * @param discoverNestedFields  if true, {@link #discoverRootFields(GenericRecord)} will return the full set of
   *                              fields, else this list will be filtered to contain only simple literals and arrays
   *                              of simple literals
   */
  public AvroFlattenerMaker(
      final boolean fromPigAvroStorage,
      final boolean binaryAsString,
      final boolean extractUnionsByType,
      final boolean discoverNestedFields
  )
  {
    this.fromPigAvroStorage = fromPigAvroStorage;
    this.binaryAsString = binaryAsString;
    this.discoverNestedFields = discoverNestedFields;

    this.avroJsonProvider = new GenericAvroJsonProvider(extractUnionsByType);
    this.jsonPathConfiguration =
        Configuration.builder()
                     .jsonProvider(avroJsonProvider)
                     .mappingProvider(new NotImplementedMappingProvider())
                     .options(EnumSet.of(Option.SUPPRESS_EXCEPTIONS))
                     .build();
  }

  @Override
  public Set<String> discoverRootFields(final GenericRecord obj)
  {
    // if discovering nested fields, just return all root fields since we want everything
    // else, we filter for literals and arrays of literals
    if (discoverNestedFields) {
      return obj.getSchema().getFields().stream().map(Schema.Field::name).collect(Collectors.toSet());
    }
    return obj.getSchema()
              .getFields()
              .stream()
              .filter(AvroFlattenerMaker::isFieldPrimitive)
              .map(Schema.Field::name)
              .collect(Collectors.toSet());
  }

  @Override
  public Object getRootField(final GenericRecord record, final String key)
  {
    return transformValue(record.get(key));
  }

  @Override
  public Function<GenericRecord, Object> makeJsonPathExtractor(final String expr)
  {
    final JsonPath jsonPath = JsonPath.compile(expr);
    return record -> transformValue(jsonPath.read(record, jsonPathConfiguration));
  }

  @Override
  public Function<GenericRecord, Object> makeJsonQueryExtractor(final String expr)
  {
    throw new UnsupportedOperationException("Avro + JQ not supported");
  }

  @Override
  public Function<GenericRecord, Object> makeJsonTreeExtractor(List<String> nodes)
  {
    if (nodes.size() == 1) {
      return (GenericRecord record) -> getRootField(record, nodes.get(0));
    }

    throw new UnsupportedOperationException("Avro + nested tree extraction not supported");
  }

  @Override
  public JsonProvider getJsonProvider()
  {
    return avroJsonProvider;
  }

  @Override
  public Object finalizeConversionForMap(Object o)
  {
    return transformValue(o);
  }

  private Object transformValue(final Object field)
  {
    if (fromPigAvroStorage && field instanceof GenericArray) {
      return Lists.transform((List) field, item -> String.valueOf(((GenericRecord) item).get(0)));
    }
    if (field instanceof ByteBuffer) {
      if (binaryAsString) {
        return StringUtils.fromUtf8(((ByteBuffer) field).array());
      } else {
        return ((ByteBuffer) field).array();
      }
    } else if (field instanceof Utf8) {
      return field.toString();
    } else if (field instanceof List) {
      return ((List<?>) field).stream().filter(Objects::nonNull).map(this::transformValue).collect(Collectors.toList());
    } else if (field instanceof GenericEnumSymbol) {
      return field.toString();
    } else if (field instanceof GenericFixed) {
      if (binaryAsString) {
        return StringUtils.fromUtf8(((GenericFixed) field).bytes());
      } else {
        return ((GenericFixed) field).bytes();
      }
    } else if (field instanceof Map) {
      LinkedHashMap<String, Object> retVal = new LinkedHashMap<>();
      Map<?, ?> fieldMap = (Map<?, ?>) field;
      for (Map.Entry<?, ?> entry : fieldMap.entrySet()) {
        retVal.put(String.valueOf(entry.getKey()), transformValue(entry.getValue()));
      }
      return retVal;
    } else if (field instanceof GenericRecord) {
      LinkedHashMap<String, Object> retVal = new LinkedHashMap<>();
      GenericRecord record = (GenericRecord) field;
      for (Schema.Field key : record.getSchema().getFields()) {
        retVal.put(key.name(), transformValue(record.get(key.pos())));
      }
      return retVal;
    }
    return field;
  }
}
