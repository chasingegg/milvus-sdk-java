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

package io.milvus.bulkwriter;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.milvus.bulkwriter.common.clientenum.BulkFileType;
import io.milvus.common.utils.ExceptionUtils;
import io.milvus.bulkwriter.common.utils.ParquetUtils;
import io.milvus.common.utils.JsonUtils;
import io.milvus.grpc.DataType;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.FieldType;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.GroupWriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

import static io.milvus.param.Constant.DYNAMIC_FIELD_NAME;

public class Buffer {
    private static final Logger logger = LoggerFactory.getLogger(Buffer.class);

    private CollectionSchemaParam collectionSchema;
    private BulkFileType fileType;
    private Map<String, List<Object>> buffer;
    private Map<String, FieldType> fields;


    public Buffer(CollectionSchemaParam collectionSchema, BulkFileType fileType) {
        this.collectionSchema = collectionSchema;
        this.fileType = fileType;

        buffer = new LinkedHashMap<>();
        fields = new LinkedHashMap<>();

        for (FieldType fieldType : collectionSchema.getFieldTypes()) {
            if (fieldType.isPrimaryKey() && fieldType.isAutoID())
                continue;
            buffer.put(fieldType.getName(), Lists.newArrayList());
            fields.put(fieldType.getName(), fieldType);
        }

        if (buffer.isEmpty()) {
            ExceptionUtils.throwUnExpectedException("Illegal collection schema: fields list is empty");
        }

        if (collectionSchema.isEnableDynamicField()) {
            buffer.put(DYNAMIC_FIELD_NAME, Lists.newArrayList());
            fields.put(DYNAMIC_FIELD_NAME, FieldType.newBuilder().withName(DYNAMIC_FIELD_NAME).withDataType(DataType.JSON).build());
        }
    }

    public Integer getRowCount() {
        if (buffer.isEmpty()) {
            return 0;
        }

        for (String fieldName : buffer.keySet()) {
            return buffer.get(fieldName).size();
        }
        return null;
    }

    public void appendRow(Map<String, Object> row) {
        for (String key : row.keySet()) {
            if (key.equals(DYNAMIC_FIELD_NAME) && !this.collectionSchema.isEnableDynamicField()) {
                continue; // skip dynamic field if it is disabled
            }
            buffer.get(key).add(row.get(key));
        }
    }

    // verify row count of fields are equal
    public List<String> persist(String localPath, Map<String, Object> config) throws IOException {
        int rowCount = -1;
        for (String key : buffer.keySet()) {
            if (rowCount < 0) {
                rowCount = buffer.get(key).size();
            } else if (rowCount != buffer.get(key).size()) {
                String msg = String.format("Column `%s` row count %s doesn't equal to the first column row count %s", key, buffer.get(key).size(), rowCount);
                ExceptionUtils.throwUnExpectedException(msg);
            }
        }

        // output files
        if (fileType == BulkFileType.PARQUET) {
            Integer bufferSize = (Integer) config.get("bufferSize");
            Integer bufferRowCount = (Integer) config.get("bufferRowCount");
            return persistParquet(localPath, bufferSize, bufferRowCount);
        } else if (fileType == BulkFileType.JSON) {
            return persistJSON(localPath);
        } else if (fileType == BulkFileType.CSV) {
            String separator = (String)config.getOrDefault("sep", "\t");
            String nullKey = (String)config.getOrDefault("nullkey", "");
            return persistCSV(localPath, separator, nullKey);
        }
        ExceptionUtils.throwUnExpectedException("Unsupported file type: " + fileType);
        return null;
    }

    private List<String> persistParquet(String localPath, Integer bufferSize, Integer bufferRowCount) throws IOException {
        String filePath = localPath + ".parquet";

        // calculate a proper row group size
        int rowGroupSizeMin = 1000;
        int rowGroupSizeMax = 1000000;
        int rowGroupSize = 10000;

        // 32MB is an experience value that avoid high memory usage of parquet reader on server-side
        int rowGroupBytes = 32 * 1024 * 1024;

        int sizePerRow = (bufferSize / bufferRowCount) + 1;
        rowGroupSize = rowGroupBytes / sizePerRow;
        rowGroupSize = Math.max(rowGroupSizeMin, Math.min(rowGroupSizeMax, rowGroupSize));

        // declare the messageType of the Parquet
        MessageType messageType = ParquetUtils.parseCollectionSchema(collectionSchema);

        // declare and define the ParquetWriter.
        Path path = new Path(filePath);
        Configuration configuration = new Configuration();
        GroupWriteSupport.setSchema(messageType, configuration);
        GroupWriteSupport writeSupport = new GroupWriteSupport();

        try (ParquetWriter<Group> writer = new ParquetWriter<>(path,
                ParquetFileWriter.Mode.CREATE,
                writeSupport,
                CompressionCodecName.UNCOMPRESSED,
                rowGroupBytes,
                5 * 1024 * 1024,
                5 * 1024 * 1024,
                ParquetWriter.DEFAULT_IS_DICTIONARY_ENABLED,
                ParquetWriter.DEFAULT_IS_VALIDATING_ENABLED,
                ParquetWriter.DEFAULT_WRITER_VERSION,
                configuration)) {

            Map<String, FieldType> nameFieldType = collectionSchema.getFieldTypes().stream().collect(Collectors.toMap(FieldType::getName, e -> e));
            if (collectionSchema.isEnableDynamicField()) {
                nameFieldType.put(DYNAMIC_FIELD_NAME, FieldType.newBuilder()
                        .withName(DYNAMIC_FIELD_NAME)
                        .withDataType(DataType.JSON)
                        .build());
            }

            List<String> fieldNameList = Lists.newArrayList(buffer.keySet());
            int size = buffer.get(fieldNameList.get(0)).size();
            for (int i = 0; i < size; ++i) {
                // build Parquet data and encapsulate it into a group.
                Group group = new SimpleGroupFactory(messageType).newGroup();
                for (String fieldName : fieldNameList) {
                    appendGroup(group, fieldName, buffer.get(fieldName).get(i), nameFieldType.get(fieldName));
                }
                writer.write(group);
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }

        String msg = String.format("Successfully persist file %s, total size: %s, row count: %s, row group size: %s",
                filePath, bufferSize, bufferRowCount, rowGroupSize);
        logger.info(msg);
        return Lists.newArrayList(filePath);
    }

    private List<String> persistJSON(String localPath) throws IOException {
        String filePath = localPath + ".json";

        Gson gson = new GsonBuilder().serializeNulls().create();
        List<Map<String, Object>> data = new ArrayList<>();

        List<String> fieldNameList = Lists.newArrayList(buffer.keySet());
        int size = buffer.get(fieldNameList.get(0)).size();
        for (int i = 0; i < size; ++i) {
            Map<String, Object> row = new HashMap<>();
            for (String fieldName : fieldNameList) {
                if (buffer.get(fieldName).get(i) instanceof ByteBuffer) {
                    row.put(fieldName, ((ByteBuffer)buffer.get(fieldName).get(i)).array());
                } else {
                    row.put(fieldName, buffer.get(fieldName).get(i));
                }
            }
            data.add(row);
        }

        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(filePath))) {
            bufferedWriter.write("[\n");
            for (int i = 0; i < data.size(); i++) {
                String json = gson.toJson(data.get(i));
                if (i != data.size()-1) {
                    json += ",";
                }
                bufferedWriter.write(json);
                bufferedWriter.newLine();
            }
            bufferedWriter.write("]\n");
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }

        return Lists.newArrayList(filePath);
    }

    private List<String> persistCSV(String localPath, String separator, String nullKey) throws IOException {
        String filePath = localPath + ".csv";

        Gson gson = new GsonBuilder().serializeNulls().create();
        List<String> fieldNameList = Lists.newArrayList(buffer.keySet());
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(filePath))) {
            bufferedWriter.write(String.join(separator, fieldNameList));
            bufferedWriter.newLine();
            int size = buffer.get(fieldNameList.get(0)).size();
            for (int i = 0; i < size; ++i) {
                List<String> values = new ArrayList<>();
                for (String fieldName : fieldNameList) {
                    Object val = buffer.get(fieldName).get(i);
                    String strVal = "";
                    if (val == null) {
                        strVal = nullKey;
                    } else if (val instanceof ByteBuffer) {
                        strVal = Arrays.toString(((ByteBuffer) val).array());
                    } else if (val instanceof List || val instanceof Map) {
                        strVal = gson.toJson(val); // server-side is using json to parse array field and vector field
                    } else {
                        strVal = val.toString();
                    }

                    // CSV format, all the single quotation should be replaced by double quotation
                    if (strVal.startsWith("\"") && strVal.endsWith("\"")) {
                        strVal = strVal.substring(1, strVal.length() - 1);
                    }
                    strVal = strVal.replace("\\\"", "\"");
                    strVal = strVal.replace("\"", "\"\"");
                    strVal = "\"" + strVal + "\"";
                    values.add(strVal);
                }

                bufferedWriter.write(String.join(separator, values));
                bufferedWriter.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }

        return Lists.newArrayList(filePath);
    }

    private void appendGroup(Group group, String paramName, Object value, FieldType fieldType) {
        DataType dataType = fieldType.getDataType();
        switch (dataType) {
            case Int8:
            case Int16:
                group.append(paramName, (Short)value);
                break;
            case Int32:
                group.append(paramName, (Integer)value);
                break;
            case Int64:
                group.append(paramName, (Long)value);
                break;
            case Float:
                group.append(paramName, (Float)value);
                break;
            case Double:
                group.append(paramName, (Double)value);
                break;
            case Bool:
                group.append(paramName, (Boolean)value);
                break;
            case VarChar:
            case String:
            case JSON:
                group.append(paramName, (String)value);
                break;
            case FloatVector:
                addFloatArray(group, paramName, (List<Float>) value);
                break;
            case BinaryVector:
            case Float16Vector:
            case BFloat16Vector:
                addBinaryVector(group, paramName, (ByteBuffer) value);
                break;
            case SparseFloatVector:
                addSparseVector(group, paramName, (SortedMap<Long, Float>) value);
                break;
            case Array:
                DataType elementType = fieldType.getElementType();
                switch (elementType) {
                    case Int8:
                    case Int16:
                    case Int32:
                        addIntArray(group, paramName, (List<Integer>) value);
                        break;
                    case Int64:
                        addLongArray(group, paramName, (List<Long>) value);
                        break;
                    case Float:
                        addFloatArray(group, paramName, (List<Float>) value);
                        break;
                    case Double:
                        addDoubleArray(group, paramName, (List<Double>) value);
                        break;
                    case String:
                    case VarChar:
                        addStringArray(group, paramName, (List<String>) value);
                        break;
                    case Bool:
                        addBooleanArray(group, paramName, (List<Boolean>) value);
                        break;
                }
        }
    }

    private static void addLongArray(Group group, String fieldName, List<Long> values) {
        Group arrayGroup = group.addGroup(fieldName);
        for (long value : values) {
            Group addGroup = arrayGroup.addGroup(0);
            addGroup.add(0, value);
        }
    }

    private static void addStringArray(Group group, String fieldName, List<String> values) {
        Group arrayGroup = group.addGroup(fieldName);
        for (String value : values) {
            Group addGroup = arrayGroup.addGroup(0);
            addGroup.add(0, value);
        }
    }

    private static void addIntArray(Group group, String fieldName, List<Integer> values) {
        Group arrayGroup = group.addGroup(fieldName);
        for (int value : values) {
            Group addGroup = arrayGroup.addGroup(0);
            addGroup.add(0, value);
        }
    }

    private static void addFloatArray(Group group, String fieldName, List<Float> values) {
        Group arrayGroup = group.addGroup(fieldName);
        for (float value : values) {
            Group addGroup = arrayGroup.addGroup(0);
            addGroup.add(0, value);
        }
    }

    private static void addDoubleArray(Group group, String fieldName, List<Double> values) {
        Group arrayGroup = group.addGroup(fieldName);
        for (double value : values) {
            Group addGroup = arrayGroup.addGroup(0);
            addGroup.add(0, value);
        }
    }

    private static void addBooleanArray(Group group, String fieldName, List<Boolean> values) {
        Group arrayGroup = group.addGroup(fieldName);
        for (boolean value : values) {
            Group addGroup = arrayGroup.addGroup(0);
            addGroup.add(0, value);
        }
    }

    private static void addBinaryVector(Group group, String fieldName, ByteBuffer byteBuffer) {
        Group arrayGroup = group.addGroup(fieldName);
        byte[] bytes = byteBuffer.array();
        for (byte value : bytes) {
            Group addGroup = arrayGroup.addGroup(0);
            addGroup.add(0, value);
        }
    }

    private static void addSparseVector(Group group, String fieldName, SortedMap<Long, Float> sparse) {
        // sparse vector is parsed as JSON format string in the server side
        String jsonString = JsonUtils.toJson(sparse);
        group.append(fieldName, jsonString);
    }
}
