/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.searchbox.searchboxsuggester;

import com.searchbox.searchboxsuggester.SuggestionResultSet.SuggestionResult;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.util.List;
import java.util.logging.Level;
import org.apache.lucene.analysis.TokenStream;
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

/**
 *
 * @author andrew
 */
public class SuggesterTreeHolder implements Serializable {
    
    static final long serialVersionUID  = SuggesterComponentParams.serialVersionUID;
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

    void computeNormalizers(int numdocs,int minDocFreq,int minTermFreq) {
        //headNode.printRecurse();;
        headNode.prune(minDocFreq,minTermFreq); //could merge nodes or rebuild tree also in here
        
        headNode.computeNormalizeTerm(0,numdocs);
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

    SuggestionResultSet getSuggestions(SolrIndexSearcher searcher, List<String> fields, String query,int maxPhraseSearch) {
        String[] queryTokens = query.split(" "); //TODO should use tokensizer..
        SuggestionResultSet rs = headNode.computeQt(queryTokens[queryTokens.length - 1],maxPhraseSearch);
        LOGGER.debug("Doing 2nd part of equation");
        try {

            if (queryTokens.length > 1) {
                SuggestionResultSet newrs = new SuggestionResultSet("unknown",maxPhraseSearch);

                //use list of pi to compute Q_c
                BooleanQuery bq = new BooleanQuery();
                for (int zz = 0; zz < queryTokens.length - 1; zz++) {
                    for (String field : fields) {
                        String token = getToken(searcher, field, queryTokens[zz]);
                        if (token != null) { //happens if token is a stopword
                            TermQuery tq = new TermQuery(new Term(field, token));
                            bq.add(tq, BooleanClause.Occur.SHOULD);
                        }
                    }

                }
                 

                // LOGGER.info("BQ1 query:\t" + bq.toString());
                DocSet qd = searcher.getDocSet(bq);


               // LOGGER.info("Number of docs in set\t" + qd.size());

                for (SuggestionResult sr : rs.suggestions) {
                    DocSet pd = searcher.getDocSet(bq).andNot(qd);
              //      LOGGER.info("Number of docs in pd set\t" + pd.size());

                    //BooleanQuery bp = new BooleanQuery();
                    String[] suggestionTokens = sr.suggestion.split(" "); //should use tokensizer
//                    for(String toprint:suggestionTokens){
//                        System.out.print(toprint+"\t");
//                    }
//                    System.out.println("");
                    for (int zz = 0; zz < suggestionTokens.length; zz++) {
                        DocSet pdinner = searcher.getDocSet(bq).andNot(qd);
                        // BooleanQuery bpinner = new BooleanQuery();
                        for (String field : fields) {
                            String token = getToken(searcher, field, suggestionTokens[zz]); //analyzer per field, not very fast...
                            if (token != null) { //happens if token is a stop word
                                //TermQuery tq = new TermQuery(new Term(field, token));
                                pdinner = pdinner.union(searcher.getDocSet(new TermQuery(new Term(field, token))));
                                //       bpinner.add(tq, BooleanClause.Occur.SHOULD);
                            }

                        }
                        /*if(bpinner.clauses().size()>0){
                         // bp.add(bpinner, BooleanClause.Occur.MUST);
                         }*/
                        if (pd.size() == 0) { //basecase
                            pd = pdinner;
                        } else {
                            pd = pd.intersection(pdinner);
                        }

                    }


                    //LOGGER.info("BQ2 query:\t" + bp.toString());
                    double Q_c = .0000001; // prevent zero bump down

                    //DocSet pd = searcher.getDocSet(p);

                    //LOGGER.info("Number of docs in phrase set\t" + pd.size());
                    if (pd.size() != 0) {
                        Q_c += qd.intersection(pd).size() / (pd.size() * 1.0);
                    }
                    //LOGGER.info("Number of docs in phrase set----- Q_c\t (" + Q_c  + ") * ("+sr.probability+")");
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
