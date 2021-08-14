package com.company.kafkaconnect;

import static org.apache.kafka.connect.transforms.util.Requirements.requireMap;
import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SchemaUtil;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

public abstract class StreamFieldInsert<R extends ConnectRecord<R>> implements Transformation<R> {

  public static final String OVERVIEW_DOC =
    "Insert a Field into a connect record";

  private interface ConfigName {
    String INSERT_FIELD_NAME = "insert.field.name";
    String INSERT_FIELD_VALUE = "insert.field.value";
  }

  public static final ConfigDef CONFIG_DEF = new ConfigDef()
    .define(ConfigName.INSERT_FIELD_NAME, ConfigDef.Type.STRING, "insertfieldNameDB", ConfigDef.Importance.HIGH,
      "Field name for Insert");
  
  public static final ConfigDef CONFIG_VALUE = new ConfigDef()
		    .define(ConfigName.INSERT_FIELD_VALUE, ConfigDef.Type.STRING, "insertfieldValueDB", ConfigDef.Importance.HIGH,
		      "Field value for Insert");

  private static final String PURPOSE = "adding New Field and Value to record";

  private String fieldName;
  
  private String fieldValue;

  private Cache<Schema, Schema> schemaUpdateCache;

  @Override
  public void configure(Map<String, ?> props) {
    final SimpleConfig config = new SimpleConfig(CONFIG_DEF, props);
    fieldName = config.getString(ConfigName.INSERT_FIELD_NAME);

    final SimpleConfig configValue = new SimpleConfig(CONFIG_VALUE, props);
    fieldValue = configValue.getString(ConfigName.INSERT_FIELD_VALUE);

    schemaUpdateCache = new SynchronizedCache<>(new LRUCache<Schema, Schema>(16));
  }


  @Override
  public R apply(R record) {
    if (operatingSchema(record) == null) {
      return applySchemaless(record);
    } else {
      return applyWithSchema(record);
    }
  }

  private R applySchemaless(R record) {
    final Map<String, Object> value = requireMap(operatingValue(record), PURPOSE);

    final Map<String, Object> updatedValue = new HashMap<>(value);

    updatedValue.put(fieldName, fieldValue);

    return newRecord(record, null, updatedValue);
  }

  private R applyWithSchema(R record) {
    final Struct value = requireStruct(operatingValue(record), PURPOSE);

    Schema updatedSchema = schemaUpdateCache.get(value.schema());
    if(updatedSchema == null) {
      updatedSchema = makeUpdatedSchema(value.schema());
      schemaUpdateCache.put(value.schema(), updatedSchema);
    }

    final Struct updatedValue = new Struct(updatedSchema);

    for (Field field : value.schema().fields()) {
      updatedValue.put(field.name(), value.get(field));
    }

    updatedValue.put(fieldName, fieldValue);

    return newRecord(record, updatedSchema, updatedValue);
  }

  @Override
  public ConfigDef config() {
    return CONFIG_DEF;
  }

  @Override
  public void close() {
    schemaUpdateCache = null;
  }


  private Schema makeUpdatedSchema(Schema schema) {
    final SchemaBuilder builder = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());

    for (Field field: schema.fields()) {
      builder.field(field.name(), field.schema());
    }

    builder.field(fieldName, Schema.STRING_SCHEMA);

    return builder.build();
  }

  protected abstract Schema operatingSchema(R record);

  protected abstract Object operatingValue(R record);

  protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue);

  public static class Key<R extends ConnectRecord<R>> extends StreamFieldInsert<R> {

    @Override
    protected Schema operatingSchema(R record) {
      return record.keySchema();
    }

    @Override
    protected Object operatingValue(R record) {
      return record.key();
    }

    @Override
    protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
      return record.newRecord(record.topic(), record.kafkaPartition(), updatedSchema, updatedValue, record.valueSchema(), record.value(), record.timestamp());
    }

  }

  public static class Value<R extends ConnectRecord<R>> extends StreamFieldInsert<R> {

    @Override
    protected Schema operatingSchema(R record) {
      return record.valueSchema();
    }

    @Override
    protected Object operatingValue(R record) {
      return record.value();
    }

    @Override
    protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
      return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(), record.key(), updatedSchema, updatedValue, record.timestamp());
    }

  }
}


