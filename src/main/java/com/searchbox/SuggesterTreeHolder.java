/*******************************************************************************
 * Copyright Searchbox - http://www.searchbox.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.searchbox;

import com.searchbox.SuggestionResultSet.SuggestionResult;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import org.apache.lucene.analysis.TokenStream;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Version;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 
 * @author andrew
 */
public class SuggesterTreeHolder implements Serializable {

	static final long serialVersionUID = SuggesterComponentParams.serialVersionUID;
	private static Logger LOGGER = LoggerFactory
			.getLogger(SuggesterTreeHolder.class);
	private TrieNode headNode;
	public boolean normalized = false;
	double logavgbifreq = 0;
	double logavgtrifreq = 0;
	private HashSet<String> nonprune;
	int ngrams;

	SuggesterTreeHolder(int NGRAMS) {
		ngrams = NGRAMS;
		headNode = new TrieNode("", ngrams);
		nonprune = new HashSet<String>();
	}

	SuggesterTreeHolder(int NGRAMS, String nonpruneFileName) {
		ngrams = NGRAMS;
		headNode = new TrieNode("", ngrams);
		nonprune = new HashSet<String>();

		if (nonpruneFileName != null) {
			loadNonPruneWords(nonpruneFileName);

			// add words from file into list to ensure that they're visible
			for (String np : nonprune) {
				TrieNode node = this.AddString(np);
				node.AddPhraseIncrementCount(np, .1);
				// random guess here....should be greater than zero
				node.docfreq = 10;
				node.termfreq = 10;
			}
		}
	}

	public TrieNode AddString(String val) {
		return headNode.AddString(val);
	}

	// normalization process as per section 3.4
	void computeNormalizers(int numdocs, int minDocFreq, int minTermFreq) {

		// could merge nodes or rebuild tree also in here
		headNode.prune(minDocFreq, minTermFreq, nonprune);

		headNode.computeNormalizeTerm(0, numdocs);
		double[] logavgfreq = new double[headNode.nc.ngramfreq.length];
		for (int zz = 0; zz < logavgfreq.length; zz++) {
			logavgfreq[zz] = Math.log10(headNode.nc.ngramfreq[zz]
					/ headNode.nc.ngramnum[zz]);
			LOGGER.info("Number of " + zz + ":\t" + headNode.nc.ngramnum[zz]);
			LOGGER.info("Freq of " + zz + ":\t" + headNode.nc.ngramfreq[zz]);
			LOGGER.info("logfreq:\t" + logavgfreq[zz]);
		}

		headNode.computeNormalizePhrase(logavgfreq);
		LOGGER.info("----------------");
		normalized = true;
		// headNode.printRecurse();
	}

	SuggestionResultSet getSuggestions(SolrIndexSearcher searcher,
			String[] fields, String query, int maxPhraseSearch) {
		query = deAccent(query);
		String[] queryTokens = query.replaceAll("[^A-Za-z0-9 ]", " ")
				.replace("  ", " ").trim().split(" "); // TODO should use
														// tokensizer..
		SuggestionResultSet rs = headNode.computeQt(
				queryTokens[queryTokens.length - 1].toLowerCase(),
				maxPhraseSearch); // get completion for the first word in the
									// suggestion

		// didn't find it, bail early
		if (rs == null) {
			return new SuggestionResultSet("", 0);
		}

		rs.myval = "";
		LOGGER.debug("Doing 2nd part of equation");
		try {

			if (queryTokens.length > 1) {

				// Solr 4.4 method change
				QueryParser parser = new QueryParser(Version.LUCENE_44,
						"contents", searcher.getCore().getLatestSchema()
								.getAnalyzer());
				// QueryParser parser = new QueryParser(Version.LUCENE_43,
				// "contents", searcher.getCore().getSchema().getAnalyzer());

				SuggestionResultSet newrs = new SuggestionResultSet("",
						maxPhraseSearch);
				StringBuilder sb = new StringBuilder();
				// build a search in all of the target fields
				for (int zz = 0; zz < queryTokens.length - 1; zz++) {
					newrs.myval = newrs.myval + queryTokens[zz] + " ";
					StringBuilder inner = new StringBuilder();
					for (String field : fields) {
						String escaped_field = parser.escape(field);
						// looking for the query token
						String escaped_token = parser.escape(queryTokens[zz]);
						inner.append(escaped_field + ":" + escaped_token + " ");
					}
					if (inner.length() > 0) {
						sb.append("+(" + inner + ")");
					}
				}

				// LOGGER.info("SB query:\t" + sb.toString());
				Query q = null;
				try {
					// convert it to a solr query
					q = parser.parse(sb.toString());
					// LOGGER.info("BQ1 query:\t" + q.toString());
				} catch (Exception e) {
					e.printStackTrace();
					LOGGER.error("Error parsing query:\t" + sb.toString());
				}
				DocSet qd = searcher.getDocSet(q);
				// LOGGER.info("Number of docs in set\t" + qd.size());

				for (SuggestionResult sr : rs.suggestions) {
					// for each of the possible suggestions, see how prevelant
					// they are in the document set so that we can know their
					// likelihood of being correct
					sb = new StringBuilder();
					// should use tokensizer
					String[] suggestionTokens = sr.suggestion.split(" ");

					for (int zz = 0; zz < suggestionTokens.length; zz++) {
						StringBuilder inner = new StringBuilder();
						for (String field : fields) {
							inner.append(field + ":" + suggestionTokens[zz]
									+ " ");
						}
						if (inner.length() > 0) {
							sb.append("+(" + inner + ")");
						}
					}

					// prevent zero bump down
					double Q_c = .0000001;

					try {
						// LOGGER.info("BQ2 query String:\t" + sb.toString());
						q = parser.parse(sb.toString());
						// LOGGER.info("BQ2 query query:\t" + q.toString());
					} catch (Exception e) {
						// LOGGER.error("parser fail?");
					}

					DocSet pd = searcher.getDocSet(q);

					// LOGGER.info("Number of docs in phrase set\t" +
					// pd.size());
					if (pd.size() != 0) {
						// As per equation (13) from paper
						Q_c += qd.intersection(pd).size() / (pd.size() * 1.0);
					}
					// LOGGER.info("Number of docs in phrase set----- Q_c\t (" +
					// Q_c + ") * (" + sr.probability + ")");
					newrs.add(sr.suggestion, sr.probability * Q_c);
				}
				rs = newrs;
			}
		} catch (IOException ex) {
			LOGGER.error(ex.getMessage());
		}
		return rs;
	}

	private void loadNonPruneWords(String nonpruneFileName) {
		BufferedReader in = null;
		if (nonpruneFileName == null) {
			return;
		}
		try {
			String workingDir = System.getProperty("user.dir");
			LOGGER.info(workingDir);
			in = new BufferedReader(new InputStreamReader(new FileInputStream(
					nonpruneFileName)));
			String line;
			while ((line = in.readLine()) != null) {
				String[] wordscore = line.split("\\s+");
				nonprune.add(wordscore[0].trim().toLowerCase());
			}
			in.close();
		} catch (Exception ex) {
			LOGGER.error("Error loading non-Prune words , format is word_string [newline]\t"
					+ ex.getMessage());
		}
	}

	static public String deAccent(String str) {
		String nfdNormalizedString = Normalizer.normalize(str,
				Normalizer.Form.NFD);
		Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
		return pattern.matcher(nfdNormalizedString).replaceAll("");
	}
}
