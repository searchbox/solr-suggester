/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.searchbox;

import com.searchbox.SuggestionResultSet.SuggestionResult;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.util.List;
import java.util.logging.Level;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Version;
import org.apache.solr.client.solrj.SolrQuery;

/**
 *
 * @author andrew
 */
public class SuggesterTreeHolder implements Serializable {

    static final long serialVersionUID = SuggesterComponentParams.serialVersionUID;
    private static Logger LOGGER = LoggerFactory.getLogger(SuggesterTreeHolder.class);
    private TrieNode headNode;
    public boolean normalized = false;
    double logavgbifreq = 0;
    double logavgtrifreq = 0;
    int ngrams;

    SuggesterTreeHolder(int NGRAMS) {
        ngrams = NGRAMS;
        headNode = new TrieNode("", ngrams);
    }

    public TrieNode AddString(String val) {
        return headNode.AddString(val);
    }

    void computeNormalizers(int numdocs, int minDocFreq, int minTermFreq) {
        //headNode.printRecurse();;
        headNode.prune(minDocFreq, minTermFreq); //could merge nodes or rebuild tree also in here

        headNode.computeNormalizeTerm(0, numdocs);
        double[] logavgfreq = new double[headNode.nc.ngramfreq.length];
        for (int zz = 0; zz < logavgfreq.length; zz++) {
            logavgfreq[zz] = Math.log10(headNode.nc.ngramfreq[zz] / headNode.nc.ngramnum[zz]);
            LOGGER.info("Number of " + zz + ":\t" + headNode.nc.ngramnum[zz]);
            LOGGER.info("Freq of " + zz + ":\t" + headNode.nc.ngramfreq[zz]);
            LOGGER.info("logfreq:\t" + logavgfreq[zz]);
        }


        headNode.computeNormalizePhrase(logavgfreq);
        LOGGER.info("----------------");
        normalized = true;
        //headNode.printRecurse();
    }

    private String getToken(SolrIndexSearcher searcher, String field, String queryWord) {
        String token = null;
        try {


            TokenStream tokenStream = searcher.getCore().getSchema().getAnalyzer().tokenStream(field, new StringReader(queryWord));
            tokenStream.reset();
            OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
            CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);

            while (tokenStream.incrementToken()) {
                int startOffset = offsetAttribute.startOffset();
                int endOffset = offsetAttribute.endOffset();
                token = charTermAttribute.toString();
            }

        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(SuggesterTreeHolder.class.getName()).log(Level.SEVERE, null, ex);
        }
        //LOGGER.debug("Took (" + queryWord + " ) and put to ( " + token + ") ");
        return token;
    }

    SuggestionResultSet getSuggestions(SolrIndexSearcher searcher, List<String> fields, String query, int maxPhraseSearch) {
        String[] queryTokens = query.split(" "); //TODO should use tokensizer..
        SuggestionResultSet rs = headNode.computeQt(queryTokens[queryTokens.length - 1], maxPhraseSearch);
        LOGGER.debug("Doing 2nd part of equation");
        try {

            if (queryTokens.length > 1) {

                QueryParser parser = new QueryParser(Version.LUCENE_40, "contents", searcher.getCore().getSchema().getAnalyzer());

                SuggestionResultSet newrs = new SuggestionResultSet("unknown", maxPhraseSearch);
                StringBuilder sb = new StringBuilder();
                for (int zz = 0; zz < queryTokens.length - 1; zz++) {
                    StringBuilder inner = new StringBuilder();
                    for (String field : fields) {
                        inner.append(field + ":" + queryTokens[zz] + " ");
                    }
                    if (inner.length() > 0) {
                        sb.append("+(" + inner + ")");
                    }
                }


               // LOGGER.info("SB query:\t" + sb.toString());
                Query q = null;
                try {
                    q = parser.parse(sb.toString());
                    //LOGGER.info("BQ1 query:\t" + q.toString());
                } catch (Exception e) {
                }
                DocSet qd = searcher.getDocSet(q);
                // LOGGER.info("Number of docs in set\t" + qd.size());

                for (SuggestionResult sr : rs.suggestions) {
                    sb = new StringBuilder();
                    String[] suggestionTokens = sr.suggestion.split(" "); //should use tokensizer

                    for (int zz = 0; zz < suggestionTokens.length; zz++) {
                        StringBuilder inner = new StringBuilder();
                        for (String field : fields) {
                            inner.append(field + ":" + suggestionTokens[zz] + " ");
                        }
                        if (inner.length() > 0) {
                            sb.append("+(" + inner + ")");
                        }
                    }



                    double Q_c = .0000001; // prevent zero bump down

                    try {
                        //LOGGER.info("BQ2 query String:\t" + sb.toString());
                        q = parser.parse(sb.toString());
                        //LOGGER.info("BQ2 query query:\t" + q.toString());
                    } catch (Exception e) {
                      //  LOGGER.error("parser fail?");
                    }



                    DocSet pd = searcher.getDocSet(q);

                    //LOGGER.info("Number of docs in phrase set\t" + pd.size());
                    if (pd.size() != 0) {
                        Q_c += qd.intersection(pd).size() / (pd.size() * 1.0);
                    }
                  //  LOGGER.info("Number of docs in phrase set----- Q_c\t (" + Q_c + ") * (" + sr.probability + ")");
                    newrs.add(sr.suggestion, sr.probability * Q_c);
                }
                rs = newrs;
            }
        } catch (IOException ex) {
            LOGGER.error(ex.getMessage());
        }
        return rs;
    }
}
