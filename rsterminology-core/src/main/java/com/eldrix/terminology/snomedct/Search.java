package com.eldrix.terminology.snomedct;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.EJBQLQuery;
import org.apache.cayenne.query.SelectQuery;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eldrix.terminology.cayenne.CayenneUtility;
import com.eldrix.terminology.snomedct.semantic.Dmd;
import com.eldrix.terminology.snomedct.semantic.RelationType;


/**
 * Provides full-text indexing and search facilities for SNOMED CT concepts (descriptions really) using Apache Lucene.
 * This provides a thin-wrapper around Apache Lucene's full-text search facilities.
 * 
 * Objects of this class are usually singleton objects for the index location specified and are 
 * essentially immutable and thread-safe. Build a search request using the Request.Builder API 
 * and then submit the request to perform a search.
 *
 */
public class Search {
	final static Logger log = LoggerFactory.getLogger(Search.class);
	final static ConcurrentHashMap<String, Search> factory = new ConcurrentHashMap<>();

	private static final int BATCH_ITERATOR_COUNT = 500;		// number of descriptions to process in a single batch.
	private static final int DEFAULT_MAXIMUM_HITS = 200;		// default maximum of hits to return.
	private static final String INDEX_LOCATION_PROPERTY_KEY="com.eldrix.snomedct.search.lucene.IndexLocation";
	private static final String DEFAULT_INDEX_LOCATION="/var/rsdb/sct_lucene6/";

	/**
	 * Names of the fields for the Lucene backend.
	 */
	private static final String FIELD_TERM="term";
	private static final String FIELD_PREFERRED_TERM="preferredTerm";
	private static final String FIELD_CONCEPT_ID="conceptId";
	private static final String FIELD_RECURSIVE_PARENT_CONCEPT_ID="recursiveParentConceptId";
	private static final String FIELD_DIRECT_PARENT_CONCEPT_ID="directParentConceptId";
	private static final String FIELD_LANGUAGE="language";
	private static final String FIELD_DESCRIPTION_STATUS="descriptionStatus";
	private static final String FIELD_CONCEPT_STATUS="conceptStatus";
	private static final String FIELD_DESCRIPTION_ID="descriptionId";
	private static final String FIELD_DESCRIPTION_ID_INDEX="descriptionIdIndex";
	private static final String FIELD_DESCRIPTION_TYPE="descriptionType";

	private Analyzer _analyzer = new StandardAnalyzer();
	private IndexSearcher _searcher;
	private String _indexLocation; 

	/**
	 * Score documents with fewer terms more highly.
	 *
	 */
	public static class SnomedSimilarity extends ClassicSimilarity {
		@Override
		public float lengthNorm(FieldInvertState state) {
			return (float) 1.0 / state.getLength();
		}
	}

	/**
	 * Some common filters to constrain the results of searches.
	 *
	 */
	public static class Filter {
		private static final long[] dmdVtmOrTfIds = new long[] { Dmd.Product.VIRTUAL_THERAPEUTIC_MOIETY.conceptId, Dmd.Product.TRADE_FAMILY.conceptId};
		private static final long[] dmdVmpOrAmpIds = new long[] { Dmd.Product.ACTUAL_MEDICINAL_PRODUCT.conceptId, Dmd.Product.VIRTUAL_MEDICINAL_PRODUCT.conceptId};

		/**
		 * Returns a filter for descriptions with one of the given parent concepts.
		 *
		 * @param parentConceptIds
		 * @return
		 */
		public static Query forRecursiveParent(long[] parentConceptIds) {
			return LongPoint.newSetQuery(FIELD_RECURSIVE_PARENT_CONCEPT_ID, parentConceptIds);
		}

		/**
		 * Returns a filter for descriptions with one of the given parent concepts.
		 *
		 * @param parentConceptIds
		 * @return
		 */
		public static Query forRecursiveParent(List<Long> parentConceptIds) {
			return LongPoint.newSetQuery(FIELD_RECURSIVE_PARENT_CONCEPT_ID, parentConceptIds);
		}

		/**
		 * Return a filter for descriptions with the given parent concept.
		 * @param parentConceptId
		 * @return
		 */
		public static Query forRecursiveParent(long parentConceptId) {
			return LongPoint.newExactQuery(FIELD_RECURSIVE_PARENT_CONCEPT_ID, parentConceptId);
		}

		/**
		 * Returns a filter for descriptions with one the given direct parents.
		 */
		public static Query forDirectParent(long[] isAParentConceptIds) {
			return LongPoint.newSetQuery(FIELD_DIRECT_PARENT_CONCEPT_ID, isAParentConceptIds);
		}
		/**
		 * Returns a filter for descriptions with one the given direct parents.
		 */
		public static Query forDirectParent(List<Long> isAParentConceptIds) {
			return LongPoint.newSetQuery(FIELD_DIRECT_PARENT_CONCEPT_ID, isAParentConceptIds);
		}
		/**
		 * Returns a filter for descriptions with the given direct parent.
		 */
		public static Query forDirectParent(long isAParentConceptId) {
			return LongPoint.newExactQuery(FIELD_DIRECT_PARENT_CONCEPT_ID, isAParentConceptId);
		}

		/**
		 * Return a filter to include only descriptions of the specified types.
		 * @param types
		 * @return
		 */
		public static Query forDescriptionType(int... types) {
			return IntPoint.newSetQuery(FIELD_DESCRIPTION_TYPE, types);
		}

		/**
		 * Return a filter to include only descriptions of the specified types.
		 * @param types
		 * @return
		 */
		public static Query forDescriptionType(Description.Type... types) {
			int[] t = new int[types.length];
			for (int i=0; i<types.length; i++) {
				t[i] = types[i].code;
			}
			return forDescriptionType(t);
		}

		/**
		 * Return a filter to include all types of description except the one specified.
		 */
		public static Query withoutDescriptionType(Description.Type exclude) {
			Description.Type[] values = Description.Type.values();
			int len = values.length;
			int[] types = new int[len-1];
			int i = 0;
			for (Description.Type type : values) {
				if (type != exclude) {
					types[i++] = type.code;
				}
			}
			return forDescriptionType(types);
		}


		/**
		 * Return concepts that are a type of VTM or TF.
		 */
		public static final Query DMD_VTM_OR_TF = forDirectParent(dmdVtmOrTfIds);

		/**
		 * Return concepts that are a type of VMP or AMP.
		 */
		public static final Query DMD_VMP_OR_AMP = forDirectParent(dmdVmpOrAmpIds);

		/**
		 * Return concepts that are active.
		 */
		public static final Query CONCEPT_ACTIVE = IntPoint.newSetQuery(FIELD_CONCEPT_STATUS, Concept.Status.activeCodes());

		/**
		 * Return descriptions of all types except fully specified names.
		 */
		public static final Query WITHOUT_FULLY_SPECIFIED_NAMES = withoutDescriptionType(Description.Type.FULLY_SPECIFIED_NAME);
	}

	/**
	 * Get a shared instance at the default location.
	 * @return
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	public static Search getInstance() throws CorruptIndexException, IOException {
		return getInstance(null);
	}

	/**
	 * Get a shared instance at the location specified
	 * @param indexLocation
	 * @return
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	public static Search getInstance(String indexLocation) throws CorruptIndexException, IOException {
		if (indexLocation == null) {
			indexLocation = System.getProperty(INDEX_LOCATION_PROPERTY_KEY, DEFAULT_INDEX_LOCATION);
		}
		Search search = factory.get(indexLocation);
		if (search == null) {
			Search created = new Search(indexLocation);
			search = factory.putIfAbsent(indexLocation, created);		// will return a value if already set by another thread
			if (search == null) {
				search = created;
			}
		}
		return search;
	}

	private Search(String indexLocation) throws CorruptIndexException, IOException {
		_indexLocation = indexLocation;
		_searcher = createSearcher();
		_searcher.setSimilarity(new SnomedSimilarity());
	}

	@Override
	public String toString() {
		return super.toString() + ": loc: `" + _indexLocation + "'";
	}

	private IndexSearcher createSearcher() throws CorruptIndexException, IOException {
		return new IndexSearcher(createOrLoadIndexReader(indexFile(), analyser()));
	}

	/**
	 * Create a new index based on all known SNOMED CT descriptions.
	 * This may take a *long* time....
	 * @throws IOException
	 * @throws LockObtainFailedException
	 * @throws CorruptIndexException
	 *
	 */
	public void processAllDescriptions(ObjectContext context) throws CorruptIndexException, LockObtainFailedException, IOException {
		IndexWriter writer = createOrLoadIndexWriter(indexFile(), analyser());
		EJBQLQuery countQuery = new EJBQLQuery("select COUNT(d) FROM Description d");
		@SuppressWarnings("unchecked") long count = ((List<Long>) context.performQuery(countQuery)).get(0);
		SelectQuery<Description> query = SelectQuery.query(Description.class);
		System.out.println("Updating search index:");
		CayenneUtility.timedBatchIterator(context, query, BATCH_ITERATOR_COUNT, count, (batch) -> {
			try {
				for (Description d : batch) {
					processDescription(writer, d);
				}
				writer.commit();			
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		System.out.println("Merging segments...");
		writer.forceMerge(1);
		writer.close();
		System.out.println("Finished updating search index");
		_searcher = createSearcher();		// create a new searcher now the index has changed.
	}

	/**
	 * Process a single description.
	 *
	 * @param writer
	 * @param d
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	protected void processDescription(IndexWriter writer, Description d) throws CorruptIndexException, IOException {
		writer.deleteDocuments(LongPoint.newExactQuery(FIELD_DESCRIPTION_ID_INDEX, d.getDescriptionId()));
		Document doc = new Document();
		doc.add(new TextField(FIELD_TERM, d.getTerm(), Store.YES));
		doc.add(new StoredField(FIELD_PREFERRED_TERM, d.getConcept().getPreferredDescription().getTerm()));
		doc.add(new TextField(FIELD_LANGUAGE, d.getLanguageCode(), Store.YES));
		doc.add(new IntPoint(FIELD_DESCRIPTION_STATUS, d.getDescriptionStatusCode()));
		doc.add(new IntPoint(FIELD_CONCEPT_STATUS, d.getConcept().getConceptStatusCode()));
		doc.add(new IntPoint(FIELD_DESCRIPTION_TYPE, d.getDescriptionTypeCode()));
		doc.add(new StoredField(FIELD_DESCRIPTION_ID, d.getDescriptionId()));		// for storage and retrieval
		doc.add(new LongPoint(FIELD_DESCRIPTION_ID_INDEX, d.getDescriptionId()));	// for indexing and search
		doc.add(new StoredField(FIELD_CONCEPT_ID, d.getConcept().getConceptId()));
		for (long parent : d.getConcept().getCachedRecursiveParents()) {
			doc.add(new LongPoint(FIELD_RECURSIVE_PARENT_CONCEPT_ID, parent));
		}
		for (Relationship parent : d.getConcept().getParentRelationshipsOfType(RelationType.IS_A)) {
			doc.add(new LongPoint(FIELD_DIRECT_PARENT_CONCEPT_ID, parent.getTargetConceptId()));
		}
		writer.addDocument(doc);
	}

	/**
	 * Create a new request builder.
	 * @return
	 */
	public Request.Builder newBuilder() {
		return new Request.Builder(this);
	}

	/**
	 * Return the location for the index.
	 */
	public String indexLocation() {
		return _indexLocation;
	}

	/**
	 * A search request. 
	 * This is immutable, so create a Request using a Request.Builder and the fluent API.
	 *
	 */
	public static class Request {
		final Search _searcher;
		final Query _query;
		final int _maxHits;

		Request(Search search, Query query, int maxHits) {
			_searcher = search;
			_query = query;
			_maxHits = maxHits;
		}

		/**
		 * Search, returning the raw TopDocs results from Lucene.
		 * @return
		 * @throws CorruptIndexException
		 * @throws IOException
		 */
		public TopDocs searchForTopDocs() throws CorruptIndexException, IOException {
			return _searcher.searcher().search(_query, _maxHits);
		}


		/**
		 * Search, returning the ordered results as a list of ResultItems.
		 * @return
		 * @throws CorruptIndexException
		 * @throws IOException
		 */
		public List<ResultItem> search() throws CorruptIndexException, IOException {
			TopDocs docs = searchForTopDocs();
			return resultsFromTopDocs(_searcher.searcher(), docs);
		}

		/**
		 * Convenience method to return the top hit from a search directly.
		 * @return
		 * @throws CorruptIndexException
		 * @throws IOException
		 */
		public ResultItem searchForSingle() throws CorruptIndexException, IOException {
			TopDocs docs = searchForTopDocs();
			ScoreDoc[] sds = docs.scoreDocs;
			if (docs.totalHits > 0 && sds.length > 0) {
				Document doc = _searcher.searcher().doc(sds[0].doc);
				return new _ResultItem(doc);
			}
			return null;
		}

		/**
		 * Search, returning the results as a list of concept identifiers.
		 * There will be no duplicates in the returned results.
		 * @return
		 * @throws CorruptIndexException
		 * @throws IOException
		 */
		public List<Long> searchForConcepts() throws CorruptIndexException, IOException {
			TopDocs docs = searchForTopDocs();
			return Search.conceptsFromTopDocs(_searcher.searcher(), docs);
		}

		/**
		 * Search, returning the results as a list of descriptions.
		 * @return
		 * @throws CorruptIndexException
		 * @throws IOException
		 */
		public List<String> searchForDescriptions() throws CorruptIndexException, IOException {
			TopDocs docs = searchForTopDocs();
			return Search.descriptionsFromTopDocs(_searcher.searcher(), docs);
		}

		/**
		 * A search request builder.
		 *
		 */
		public static class Builder {
			private static final int MINIMUM_CHARS_FOR_PREFIX_SEARCH = 3;
			private static final int MINIMUM_CHARS_FOR_FUZZY_SEARCH = MINIMUM_CHARS_FOR_PREFIX_SEARCH;
			private static final int DEFAULT_FUZZY_MAXIMUM_EDITS = 2;
			Search _searcher;
			int _maxHits = DEFAULT_MAXIMUM_HITS;
			int _fuzzyMaxEdits = 0;
			String _searchText;
			Query _query;
			ArrayList<Query> _filters;
			QueryParser _queryParser;
			private StandardAnalyzer _analyzer = new StandardAnalyzer();

			Builder(Search searcher) {
				Objects.requireNonNull(searcher);
				_searcher = searcher;
			}

			protected QueryParser queryParser() {
				if (_queryParser == null) {
					_queryParser = new QueryParser(FIELD_TERM, _analyzer);
					_queryParser.setDefaultOperator(QueryParser.Operator.AND);
					_queryParser.setMultiTermRewriteMethod(MultiTermQuery.SCORING_BOOLEAN_REWRITE);
				}
				return _queryParser;
			}

			/**
			 * Optional method to customise the query parser used in parsing string queries.
			 * @param parser
			 * @return
			 */
			public Builder setQueryParser(QueryParser parser) {
				_queryParser = parser;
				return this;
			}

			/**
			 * Set the maximum number of hits to be returned.
			 * @param hits
			 * @return
			 */
			public Builder setMaxHits(int hits) {
				_maxHits = hits;
				return this;
			}

			/**
			 * Set the main query for this search.
			 * This allows complete control over the query by client applications
			 * but for most uses, specifying the search string with or without a
			 * query parser will be sufficient.
			 * @param query
			 * @return
			 */
			public Builder searchUsingQuery(Query query) {
				_query = query;
				return this;
			}


			/**
			 * Search by parsing the search text using a "Query Parser".
			 * For most user-entered strings, it is recommended to use a plain string.
			 * This uses a default query parser unless a custom one is set <emph>before</emph>
			 * calling this method. 
			 * @return
			 * @throws ParseException
			 */
			public Builder searchUsingQueryParser(String searchText) throws ParseException {
				_query = queryParser().parse(searchText);
				return this;
			}

			/**
			 * Search for a SNOMED-CT term, for each token, a term query and prefix query.
			 * This is the default "built-in" parser and is usually a good default for most SNOMED-CT searches.
			 * If more control is required, either pass a string to the QueryParser using searchByParsing()
			 * or directly set the query manually.
			 * @param search
			 * @return
			 */
			public Builder search(String search) {
				_searchText = search;
				_query = null;
				return this;
			}

			/**
			 * Turn on fuzzy matching with the specified number of edits when the built-in parser is used.
			 * @param distance
			 * @return
			 */
			public Builder useFuzzy(int distance) {
				_fuzzyMaxEdits = distance > 2 ? 2 : distance;
				return this;
			}

			/**
			 * Turn on fuzzy matching with the default number of edits.
			 * @return
			 */
			public Builder useFuzzy() {
				_fuzzyMaxEdits = DEFAULT_FUZZY_MAXIMUM_EDITS;
				return this;
			}

			/**
			 * Filter only for concepts with the specified (recursive) parents.
			 * @param parents
			 * @return
			 */
			public Builder withRecursiveParent(long[] parents) {
				return withFilters(Search.Filter.forRecursiveParent(parents));
			}

			public Builder withRecursiveParent(List<Long> parents) {
				return withFilters(Search.Filter.forRecursiveParent(parents));
			}

			public Builder withRecursiveParent(long parent) {
				return withFilters(Search.Filter.forRecursiveParent(parent));
			}

			/**
			 * Include only concepts with the specified direct parents.
			 * @param isA
			 * @return
			 */
			public Builder withDirectParent(long[] isA) {
				return withFilters(Search.Filter.forDirectParent(isA));
			}

			public Builder withDirectParent(List<Long> isA) {
				return withFilters(Search.Filter.forDirectParent(isA));
			}

			public Builder withDirectParent(long isA) {
				return withFilters(Search.Filter.forDirectParent(isA));
			}

			/**
			 * Exclude descriptions that represent the FSN (fully specified name).
			 * @return
			 */
			public Builder withoutFullySpecifiedNames() {
				return withFilters(Search.Filter.WITHOUT_FULLY_SPECIFIED_NAMES);
			}

			/**
			 * Include only active concepts during search.
			 * @return
			 */
			public Builder onlyActive() {
				return withFilters(Search.Filter.CONCEPT_ACTIVE);
			}

			/**
			 * Filter for concepts with the specified queries.
			 * @param queries
			 * @return
			 */
			public Builder withFilters(Query...queries) {
				if (_filters == null) {
					_filters = new ArrayList<>(queries.length);
				}
				for (Query q : queries) {
					_filters.add(q);
				}
				return this;
			}

			/**
			 * Clear all currently set filters.
			 * This does not change the search term(s) however.
			 * @return
			 */
			public Builder clearFilters() {
				_filters.clear();
				return this;
			}

			// determine query from plain search string with optional fuzziness.
			private static Query queryFromString(Analyzer analyzer, String searchText, int fuzzy) {
				if (searchText != null && !searchText.isEmpty()) {
					BooleanQuery.Builder b = new BooleanQuery.Builder();
					try (TokenStream stream = analyzer.tokenStream(FIELD_TERM, searchText)) {
						CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
						stream.reset();
						while (stream.incrementToken()) {
							String s = termAtt.toString();
							Term term = new Term("term", s);
							Query tq = s.length() > MINIMUM_CHARS_FOR_FUZZY_SEARCH && fuzzy > 0 ? new FuzzyQuery(term, fuzzy) : new TermQuery(term);
							if (s.length() >= MINIMUM_CHARS_FOR_PREFIX_SEARCH) {
								PrefixQuery pq = new PrefixQuery(new Term("term", s));
								pq.setRewriteMethod(PrefixQuery.SCORING_BOOLEAN_REWRITE);
								BooleanQuery bq = new BooleanQuery.Builder().add(tq, Occur.SHOULD).add(pq, Occur.SHOULD).build();
								b.add(bq, Occur.MUST);
							}
							else {
								b.add(tq,Occur.MUST);
							}
						}
						stream.end();
					} catch (IOException e) {
						e.printStackTrace();
					}
					return b.build();
				}
				return null;
			}

			/**
			 * Create the search request.
			 * @return
			 */
			public Request build() {
				Query query = _query != null ? _query : queryFromString(_analyzer, _searchText, _fuzzyMaxEdits);
				if (_filters != null && _filters.size() > 0) {
					BooleanQuery.Builder bqBuilder = new BooleanQuery.Builder();
					if (query != null) {
						bqBuilder.add(query, Occur.MUST);		// add the basic query if it exists
					}
					for (Query q : _filters) {
						bqBuilder.add(q, Occur.FILTER);		// and add each filter as a filter.
					}
					query = bqBuilder.build();
				}
				return new Request(_searcher, query, _maxHits);
			}
		}
	}

	/**
	 * Helper method to return an array from the search result.
	 * @param searcher
	 * @param docs
	 * @return
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	private static List<ResultItem> resultsFromTopDocs(IndexSearcher searcher, TopDocs docs) throws CorruptIndexException, IOException {
		ArrayList<ResultItem> results = new ArrayList<ResultItem>(docs.totalHits);
		ScoreDoc[] sds = docs.scoreDocs;
		for (ScoreDoc sd : sds) {
			Document doc = searcher.doc(sd.doc);
			results.add(new _ResultItem(doc));
		}
		return results;
	}

	/**
	 * Helper method to return an array of the text from the descriptions from the search result.
	 * @param searcher
	 * @param docs
	 * @return
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	private static List<String> descriptionsFromTopDocs(IndexSearcher searcher, TopDocs docs) throws CorruptIndexException, IOException {
		ArrayList<String> descs = new ArrayList<String>(docs.totalHits);
		ScoreDoc[] sds = docs.scoreDocs;
		for (ScoreDoc sd : sds) {
			Document doc = searcher.doc(sd.doc);
			IndexableField term = doc.getField(FIELD_TERM);
			descs.add(term.stringValue());
		}
		return Collections.unmodifiableList(descs);
	}

	/**
	 * Helper method to return an array of the concept identifiers matching the search result.
	 * There will be no duplicates returned.
	 * @param searcher
	 * @param docs
	 * @return
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	private static List<Long> conceptsFromTopDocs(IndexSearcher searcher, TopDocs docs) throws CorruptIndexException, IOException {
		LinkedHashSet<Long> concepts = new LinkedHashSet<>(docs.totalHits);
		ScoreDoc[] sds = docs.scoreDocs;
		for (ScoreDoc sd : sds) {
			Document doc = searcher.doc(sd.doc);
			IndexableField conceptField = doc.getField(FIELD_CONCEPT_ID);
			Long conceptId = conceptField.numericValue().longValue();
			concepts.add(conceptId);
		}
		return new ArrayList<>(concepts);
	}

	/*
	 * Returns the IndexSearcher used to search against the Lucene index.
	 */
	protected IndexSearcher searcher() throws CorruptIndexException, IOException {
		return _searcher;
	}

	/**
	 * This loads an IndexReader in read-only mode.
	 * @return
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	protected static IndexReader createOrLoadIndexReader(URI index, Analyzer analyser) throws CorruptIndexException, IOException {
		Directory directory = FSDirectory.open(Paths.get(index));
		if (DirectoryReader.indexExists(directory) == false) {
			IndexWriter writer = createOrLoadIndexWriter(index, analyser);
			writer.close();
		}
		IndexReader reader = DirectoryReader.open(directory);
		return reader;
	}

	protected static IndexWriter createOrLoadIndexWriter(URI index, Analyzer analyser) throws CorruptIndexException, LockObtainFailedException, IOException  {
		Directory directory = FSDirectory.open(Paths.get(index));
		IndexWriterConfig iwc = new IndexWriterConfig(analyser);
		iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
		iwc.setSimilarity(new SnomedSimilarity());
		IndexWriter writer = new IndexWriter(directory, iwc);
		return writer;
	}

	protected URI indexFile() {
		return new File(_indexLocation).toURI();
	}

	protected Analyzer analyser() {
		return _analyzer;
	}

	/**
	 * Generates a fake Lucene full-text search result containing a single result of the concept specified.
	 * This is useful in faking the initial results of a search before any real search has taken place for example.
	 * @param concept
	 * @return
	 */
	public static ResultItem resultForConcept(Concept concept) {
		if (concept == null) {
			return null;
		}
		return new _ResultItem(concept);
	}
	public static ResultItem resultForConcept(long conceptId, String term, String preferredTerm) {
		return new _ResultItem(term, conceptId, preferredTerm);
	}


	/**
	 * A result of a search.
	 */
	public interface ResultItem {
		public String getTerm();
		public long getConceptId();
		public String getPreferredTerm();
	}

	protected static class _ResultItem implements ResultItem {
		private final String _term;
		private final long _conceptId;
		private final String _preferredTerm;

		protected _ResultItem(String term, long conceptId, String preferredTerm) {
			_term = term;
			_conceptId = conceptId;
			_preferredTerm = preferredTerm;
		}

		protected _ResultItem(Document doc) {
			_term = doc.getField(FIELD_TERM).stringValue();
			_conceptId = Long.parseLong(doc.getField(FIELD_CONCEPT_ID).stringValue());
			IndexableField preferredTerm = doc.getField(FIELD_PREFERRED_TERM);
			_preferredTerm = preferredTerm != null ? preferredTerm.stringValue() : _term;
		}
		protected _ResultItem(Concept concept) {
			_term = concept.getPreferredDescription().getTerm();
			_conceptId = concept.getConceptId();
			_preferredTerm = _term;
		}

		@Override
		public boolean equals(Object obj) {
			if (super.equals(obj)) {
				return true;
			}
			if (obj instanceof _ResultItem) {
				_ResultItem ri = (_ResultItem) obj;
				return _conceptId == ri._conceptId && _preferredTerm == ri._preferredTerm && _term == ri._term;
			}
			return false;
		}
		@Override
		public int hashCode() {
			return Objects.hash(_conceptId, _preferredTerm, _term);
		}

		@Override
		public String getTerm() {
			return _term;
		}
		@Override
		public long getConceptId() {
			return _conceptId;
		}
		@Override
		public String getPreferredTerm() {
			return _preferredTerm;
		}
		@Override
		public String toString() {
			return super.toString() + ": " + getTerm() + " (" + getConceptId() + ")";
		}
	}

	/**
	 * Parse a list of long numbers delimited by commas into an array.
	 * @param list
	 * @return
	 */
	public static long[] parseLongArray(String list) {
		if (list != null && list.length() > 0) {
			String[] roots = list.split(",");
			long[] rootConceptIds = new long[roots.length];
			try {
				for (int i=0; i<roots.length; i++) {
					rootConceptIds[i] = Long.parseLong(roots[i]);
				}
				return rootConceptIds;
			}
			catch (NumberFormatException e) {
				;	//NOP
			}
		}
		return new long[] {} ;
	}
}
