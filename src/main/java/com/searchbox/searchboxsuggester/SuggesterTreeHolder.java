/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.searchbox.searchboxsuggester;

import com.searchbox.searchboxsuggester.SuggestionResultSet.SuggestionResult;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.logging.Level;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author andrew
 */
public class SuggesterTreeHolder implements Serializable {

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

    void computeNormalizers() {
        //headNode.printRecurse();;
        headNode.computeNormalizeTerm(0);
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

    SuggestionResultSet getSuggestions(SolrIndexSearcher searcher, List<String> fields, String query) {
        String[] queryTokens = query.split(" "); //should use tokensizer
        SuggestionResultSet rs = headNode.computeQt(queryTokens[queryTokens.length - 1]);
        LOGGER.info("going into stage 2");
        try {
            if (queryTokens.length > 1) {
                SuggestionResultSet newrs = new SuggestionResultSet("unknown");
                //use list of pi to compute Q_c
                BooleanQuery bq = new BooleanQuery();
                for (int zz = 0; zz < queryTokens.length - 1; zz++) {
                    for (String field : fields) {
                        TermQuery tq = new TermQuery(new Term(field, queryTokens[zz]));
                        bq.add(tq, BooleanClause.Occur.SHOULD);
                        break;
                    }

                }

                LOGGER.info("BQ1 query:\t"+bq.toString());
                DocSet qd = searcher.getDocSet(bq);
                LOGGER.info("Number of docs in set\t" + qd.size());

                for (SuggestionResult sr : rs.suggestions) {
                    
                    BooleanQuery bp = new BooleanQuery();
                    String [] suggestionTokens= sr.suggestion.split(" "); //should use tokensizer
                    for (int zz = 0; zz < suggestionTokens.length; zz++) {
                        for (String field : fields) {
                            TermQuery tq = new TermQuery(new Term(field, suggestionTokens[zz]));
                            bp.add(tq, BooleanClause.Occur.SHOULD);
                        }
                    }
                    LOGGER.info("BQ2 query:\t"+bp.toString());
                    double Q_c;

                    DocSet pd = searcher.getDocSet(bq);
                    LOGGER.info("Number of docs in phrase set\t" + pd.size());
                    if (pd.size() != 0) {
                        Q_c = qd.intersection(pd).size() / (pd.size() * 1.0);
                    } else {
                        Q_c = 0;
                    }
                    LOGGER.info("Number of docs in phrase set----- Q_c\t" + Q_c);
                    newrs.add(sr.suggestion, sr.probability*Q_c);
                }
                rs=newrs;
            }
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(SuggesterTreeHolder.class.getName()).log(Level.SEVERE, null, ex);
        }
        return rs;
    }
}
