package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Bm25Retriever {

    public record Doc(String text, ChunkMetadata metadata) {
    }

    private final Directory directory = new ByteBuffersDirectory();
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private final Map<Integer, ChunkMetadata> metadataById = new HashMap<>();

    public void index(List<Doc> docs) {
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            writer.deleteAll();
            metadataById.clear();
            for (int i = 0; i < docs.size(); i++) {
                Doc doc = docs.get(i);
                Document lucene = new Document();
                lucene.add(new TextField("body", searchableText(doc), Field.Store.NO));
                lucene.add(new StoredField("text", doc.text()));
                lucene.add(new StoredField("id", i));
                writer.addDocument(lucene);
                metadataById.put(i, doc.metadata());
            }
        } catch (Exception e) {
            throw new RuntimeException("Bm25 index error", e);
        }
    }

    public List<Content> retrieve(Query query, int topK) {
        String text = query.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            org.apache.lucene.search.Query parsed = new QueryParser("body", analyzer)
                    .parse(QueryParser.escape(text));
            TopDocs topDocs = searcher.search(parsed, topK);

            List<Content> hits = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = reader.storedFields().document(scoreDoc.doc);
                int id = doc.getField("id").numericValue().intValue();
                ChunkMetadata metadata = metadataById.get(id);
                TextSegment segment = TextSegment.from(doc.get("text"), toMetadata(metadata));
                hits.add(Content.from(segment));
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("Bm25 query error", e);
        }
    }

    private static Metadata toMetadata(ChunkMetadata metadata) {
        return Metadata.from(Map.of(
                "source_file", metadata.sourceFile(),
                "section", metadata.section() == null ? "" : metadata.section(),
                "citation_id", metadata.citationId()
        ));
    }

    private static String searchableText(Doc doc) {
        ChunkMetadata metadata = doc.metadata();
        return doc.text() + "\n"
                + metadata.sourceFile().replace(".txt", "") + "\n"
                + (metadata.section() == null ? "" : metadata.section());
    }
}
