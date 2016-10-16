/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.elasticsearch.ElasticsearchGenerationException;
import org.elasticsearch.Version;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.StringAndBytesText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.util.concurrent.ReleasableLock;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.mapper.Mapping.SourceTransform;
import org.elasticsearch.index.mapper.MetadataFieldMapper.TypeParser;
import org.elasticsearch.index.mapper.internal.AllFieldMapper;
import org.elasticsearch.index.mapper.internal.IdFieldMapper;
import org.elasticsearch.index.mapper.internal.IndexFieldMapper;
import org.elasticsearch.index.mapper.internal.NodeFieldMapper;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.mapper.internal.RoutingFieldMapper;
import org.elasticsearch.index.mapper.internal.SourceFieldMapper;
import org.elasticsearch.index.mapper.internal.TTLFieldMapper;
import org.elasticsearch.index.mapper.internal.TimestampFieldMapper;
import org.elasticsearch.index.mapper.internal.TokenFieldMapper;
import org.elasticsearch.index.mapper.internal.TypeFieldMapper;
import org.elasticsearch.index.mapper.internal.UidFieldMapper;
import org.elasticsearch.index.mapper.object.ObjectMapper;
import org.elasticsearch.index.mapper.object.RootObjectMapper;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.search.internal.SearchContext;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

/**
 *
 */
public class DocumentMapper implements ToXContent {

    public static class Builder {

        private Map<Class<? extends MetadataFieldMapper>, MetadataFieldMapper> metadataMappers = new LinkedHashMap<>();

        private List<SourceTransform> sourceTransforms = new ArrayList<>(1);

        private final Settings indexSettings;

        private final RootObjectMapper rootObjectMapper;

        private ImmutableMap<String, Object> meta = ImmutableMap.of();

        private final Mapper.BuilderContext builderContext;

        public Builder(Settings indexSettings, RootObjectMapper.Builder builder, MapperService mapperService) {
            this.indexSettings = indexSettings;
            this.builderContext = new Mapper.BuilderContext(indexSettings, new ContentPath(1));
            this.rootObjectMapper = builder.build(builderContext);

            for (Map.Entry<String, MetadataFieldMapper.TypeParser> entry : mapperService.mapperRegistry.getMetadataMapperParsers().entrySet()) {
                final String name = entry.getKey();
                final TypeParser parser = entry.getValue();
                final MetadataFieldMapper metadataMapper = parser.getDefault(indexSettings, mapperService.fullName(name), builder.name());
                metadataMappers.put(metadataMapper.getClass(), metadataMapper);
            }
        }

        public Builder meta(ImmutableMap<String, Object> meta) {
            this.meta = meta;
            return this;
        }

        public Builder put(MetadataFieldMapper.Builder<?, ?> mapper) {
            MetadataFieldMapper metadataMapper = mapper.build(builderContext);
            metadataMappers.put(metadataMapper.getClass(), metadataMapper);
            return this;
        }

        public Builder transform(ScriptService scriptService, Script script) {
            sourceTransforms.add(new ScriptTransform(scriptService, script));
            return this;
        }

        /**
         * @deprecated Use {@link #transform(ScriptService, Script)} instead.
         */
        @Deprecated
        public Builder transform(ScriptService scriptService, String script, ScriptType scriptType, String language,
                Map<String, Object> parameters) {
            sourceTransforms.add(new ScriptTransform(scriptService, new Script(script, scriptType, language, parameters)));
            return this;
        }

        public DocumentMapper build(MapperService mapperService, DocumentMapperParser docMapperParser) {
            Preconditions.checkNotNull(rootObjectMapper, "Mapper builder must have the root object mapper set");
            return new DocumentMapper(mapperService, indexSettings, docMapperParser, rootObjectMapper, meta, metadataMappers, sourceTransforms, mapperService.mappingLock);
        }
    }

    private final MapperService mapperService;

    private final String type;
    private final StringAndBytesText typeText;

    private volatile CompressedXContent mappingSource;

    private final Mapping mapping;

    private final DocumentParser documentParser;

    private volatile DocumentFieldMappers fieldMappers;

    private volatile ImmutableMap<String, ObjectMapper> objectMappers = ImmutableMap.of();

    private boolean hasNestedObjects = false;

    private final ReleasableLock mappingWriteLock;
    private final ReentrantReadWriteLock mappingLock;

    public DocumentMapper(MapperService mapperService, @Nullable Settings indexSettings, DocumentMapperParser docMapperParser,
                          RootObjectMapper rootObjectMapper,
                          ImmutableMap<String, Object> meta,
                          Map<Class<? extends MetadataFieldMapper>, MetadataFieldMapper> metadataMappers,
                          List<SourceTransform> sourceTransforms,
                          ReentrantReadWriteLock mappingLock) {
        this.mapperService = mapperService;
        this.type = rootObjectMapper.name();
        this.typeText = new StringAndBytesText(this.type);
        this.mapping = new Mapping(
                Version.indexCreated(indexSettings),
                rootObjectMapper,
                metadataMappers.values().toArray(new MetadataFieldMapper[metadataMappers.values().size()]),
                sourceTransforms.toArray(new SourceTransform[sourceTransforms.size()]),
                meta);
        this.documentParser = new DocumentParser(indexSettings, docMapperParser, this, new ReleasableLock(mappingLock.readLock()));

        this.mappingWriteLock = new ReleasableLock(mappingLock.writeLock());
        this.mappingLock = mappingLock;

        if (metadataMapper(ParentFieldMapper.class).active()) {
            // mark the routing field mapper as required
            metadataMapper(RoutingFieldMapper.class).markAsRequired();
        }

        // collect all the mappers for this type
        List<ObjectMapper> newObjectMappers = new ArrayList<>();
        List<FieldMapper> newFieldMappers = new ArrayList<>();
        for (MetadataFieldMapper metadataMapper : this.mapping.metadataMappers) {
            if (metadataMapper instanceof FieldMapper) {
                newFieldMappers.add(metadataMapper);
            }
        }
        MapperUtils.collect(this.mapping.root, newObjectMappers, newFieldMappers);

        this.fieldMappers = new DocumentFieldMappers(docMapperParser.analysisService).copyAndAllAll(newFieldMappers);
        this.objectMappers = Maps.uniqueIndex(newObjectMappers, new Function<ObjectMapper, String>() {
            @Override
            public String apply(ObjectMapper mapper) {
                return mapper.fullPath();
            }
        });
        for (ObjectMapper objectMapper : newObjectMappers) {
            if (objectMapper.nested().isNested()) {
                hasNestedObjects = true;
            }
        }

        refreshSource();
    }

    public Mapping mapping() {
        return mapping;
    }

    public String type() {
        return this.type;
    }

    public Text typeText() {
        return this.typeText;
    }

    public ImmutableMap<String, Object> meta() {
        return mapping.meta;
    }

    public CompressedXContent mappingSource() {
        return this.mappingSource;
    }

    public RootObjectMapper root() {
        return mapping.root;
    }

    public UidFieldMapper uidMapper() {
        return metadataMapper(UidFieldMapper.class);
    }

    @SuppressWarnings({"unchecked"})
    public <T extends MetadataFieldMapper> T metadataMapper(Class<T> type) {
        return mapping.metadataMapper(type);
    }

    public IndexFieldMapper indexMapper() {
        return metadataMapper(IndexFieldMapper.class);
    }

    public TypeFieldMapper typeMapper() {
        return metadataMapper(TypeFieldMapper.class);
    }

    public SourceFieldMapper sourceMapper() {
        return metadataMapper(SourceFieldMapper.class);
    }

    public AllFieldMapper allFieldMapper() {
        return metadataMapper(AllFieldMapper.class);
    }

    public IdFieldMapper idFieldMapper() {
        return metadataMapper(IdFieldMapper.class);
    }

    public RoutingFieldMapper routingFieldMapper() {
        return metadataMapper(RoutingFieldMapper.class);
    }

    public TokenFieldMapper tokenFieldMapper() {
        return metadataMapper(TokenFieldMapper.class);
    }
    
    public NodeFieldMapper nodeFieldMapper() {
        return metadataMapper(NodeFieldMapper.class);
    }
    
    public ParentFieldMapper parentFieldMapper() {
        return metadataMapper(ParentFieldMapper.class);
    }

    public TimestampFieldMapper timestampFieldMapper() {
        return metadataMapper(TimestampFieldMapper.class);
    }

    public TTLFieldMapper TTLFieldMapper() {
        return metadataMapper(TTLFieldMapper.class);
    }

    public IndexFieldMapper IndexFieldMapper() {
        return metadataMapper(IndexFieldMapper.class);
    }

    public Query typeFilter() {
        return typeMapper().fieldType().termQuery(type, null);
    }

    public boolean hasNestedObjects() {
        return hasNestedObjects;
    }

    public DocumentFieldMappers mappers() {
        return this.fieldMappers;
    }

    public ImmutableMap<String, ObjectMapper> objectMappers() {
        return this.objectMappers;
    }

    public ParsedDocument parse(String index, String type, String id, BytesReference source) throws MapperParsingException {
        return parse(SourceToParse.source(source).index(index).type(type).id(id));
    }

    public ParsedDocument parse(SourceToParse source) throws MapperParsingException {
        return documentParser.parseDocument(source);
    }

    /**
     * Returns the best nested {@link ObjectMapper} instances that is in the scope of the specified nested docId.
     */
    public ObjectMapper findNestedObjectMapper(int nestedDocId, SearchContext sc, LeafReaderContext context) throws IOException {
        ObjectMapper nestedObjectMapper = null;
        for (ObjectMapper objectMapper : objectMappers().values()) {
            if (!objectMapper.nested().isNested()) {
                continue;
            }

            Filter filter = objectMapper.nestedTypeFilter();
            if (filter == null) {
                continue;
            }
            // We can pass down 'null' as acceptedDocs, because nestedDocId is a doc to be fetched and
            // therefor is guaranteed to be a live doc.
            DocIdSet nestedTypeSet = filter.getDocIdSet(context, null);
            if (nestedTypeSet == null) {
                continue;
            }
            DocIdSetIterator iterator = nestedTypeSet.iterator();
            if (iterator == null) {
                continue;
            }

            if (iterator.advance(nestedDocId) == nestedDocId) {
                if (nestedObjectMapper == null) {
                    nestedObjectMapper = objectMapper;
                } else {
                    if (nestedObjectMapper.fullPath().length() < objectMapper.fullPath().length()) {
                        nestedObjectMapper = objectMapper;
                    }
                }
            }
        }
        return nestedObjectMapper;
    }

    /**
     * Returns the parent {@link ObjectMapper} instance of the specified object mapper or <code>null</code> if there
     * isn't any.
     */
    // TODO: We should add: ObjectMapper#getParentObjectMapper()
    public ObjectMapper findParentObjectMapper(ObjectMapper objectMapper) {
        int indexOfLastDot = objectMapper.fullPath().lastIndexOf('.');
        if (indexOfLastDot != -1) {
            String parentNestObjectPath = objectMapper.fullPath().substring(0, indexOfLastDot);
            return objectMappers().get(parentNestObjectPath);
        } else {
            return null;
        }
    }

    /**
     * Transform the source when it is expressed as a map.  This is public so it can be transformed the source is loaded.
     * @param sourceAsMap source to transform.  This may be mutated by the script.
     * @return transformed version of transformMe.  This may actually be the same object as sourceAsMap
     */
    public Map<String, Object> transformSourceAsMap(Map<String, Object> sourceAsMap) {
        return DocumentParser.transformSourceAsMap(mapping, sourceAsMap);
    }

    public boolean isParent(String type) {
        return mapperService.getParentTypes().contains(type);
    }

    private void addMappers(Collection<ObjectMapper> objectMappers, Collection<FieldMapper> fieldMappers, boolean updateAllTypes) {
        assert mappingLock.isWriteLockedByCurrentThread();
        // first ensure we don't have any incompatible new fields
        mapperService.checkNewMappersCompatibility(objectMappers, fieldMappers, updateAllTypes);

        // update mappers for this document type
        MapBuilder<String, ObjectMapper> builder = MapBuilder.newMapBuilder(this.objectMappers);
        for (ObjectMapper objectMapper : objectMappers) {
            builder.put(objectMapper.fullPath(), objectMapper);
            if (objectMapper.nested().isNested()) {
                hasNestedObjects = true;
            }
        }
        this.objectMappers = builder.immutableMap();
        this.fieldMappers = this.fieldMappers.copyAndAllAll(fieldMappers);

        // finally update for the entire index
        mapperService.addMappers(objectMappers, fieldMappers);
    }

    public MergeResult merge(Mapping mapping, boolean simulate, boolean updateAllTypes) {
        try (ReleasableLock lock = mappingWriteLock.acquire()) {
            final MergeResult mergeResult = new MergeResult(simulate, updateAllTypes);
            this.mapping.merge(mapping, mergeResult);
            if (simulate == false) {
                addMappers(mergeResult.getNewObjectMappers(), mergeResult.getNewFieldMappers(), updateAllTypes);
                refreshSource();
            }
            return mergeResult;
        }
    }

    private void refreshSource() throws ElasticsearchGenerationException {
        try {
            mappingSource = new CompressedXContent(this, XContentType.JSON, ToXContent.EMPTY_PARAMS);
        } catch (Exception e) {
            throw new ElasticsearchGenerationException("failed to serialize source for type [" + type + "]", e);
        }
    }

    public void close() {
        documentParser.close();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return mapping.toXContent(builder, params);
    }

    /**
     * Script based source transformation.
     */
    private static class ScriptTransform implements SourceTransform {
        private final ScriptService scriptService;
        /**
         * The script to transform the source document before indexing.
         */
        private final Script script;

        public ScriptTransform(ScriptService scriptService, Script script) {
            this.scriptService = scriptService;
            this.script = script;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> transformSourceAsMap(Map<String, Object> sourceAsMap) {
            try {
                // We use the ctx variable and the _source name to be consistent with the update api.
                ExecutableScript executable = scriptService.executable(script, ScriptContext.Standard.MAPPING, null);
                Map<String, Object> ctx = new HashMap<>(1);
                ctx.put("_source", sourceAsMap);
                executable.setNextVar("ctx", ctx);
                executable.run();
                ctx = (Map<String, Object>) executable.unwrap(ctx);
                return (Map<String, Object>) ctx.get("_source");
            } catch (Exception e) {
                throw new IllegalArgumentException("failed to execute script", e);
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return script.toXContent(builder, params);
        }
    }
}