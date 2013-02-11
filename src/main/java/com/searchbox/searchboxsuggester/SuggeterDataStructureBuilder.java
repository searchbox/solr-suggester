/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.searchbox.searchboxsuggester;


import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.util.Bits;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.LoggerFactory;

/**
 *
 * @author andrew
 */
public class SuggeterDataStructureBuilder {

    private static org.slf4j.Logger LOGGER = LoggerFactory.getLogger(SuggesterComponent.class);
    private SentenceDetectorME sentenceDetector = null;
    private String sentenceDetectorModelName = "en-sent.bin";
    private TokenizerME tokenizer = null;
    private String tokenizerModelName = "en-token.bin";
    private String stopWordsFileName = "stopwords_for_suggestor.txt";
    private HashSet<String> stopwords = new HashSet<String>();
    public int NGRAMS = 4;
    public int counts[] = new int[NGRAMS];
    private SuggesterTreeHolder suggester = new SuggesterTreeHolder(NGRAMS);

    /*------------*/
    public void Tokenizer(String filename_model) throws FileNotFoundException {
        InputStream modelIn = (getClass().getResourceAsStream("/"+filename_model));
        try {
            TokenizerModel model = new TokenizerModel(modelIn);
            tokenizer = new TokenizerME(model);
        } catch (IOException e) {
        }
    }

    public String[] getTokens(String fulltext) {
        return tokenizer.tokenize(fulltext);
    }
    /*------------*/

    /*------------*/
    public void SentenceParser(String filename_model) throws FileNotFoundException {
        InputStream modelIn = (getClass().getResourceAsStream("/"+filename_model));
        try {
            SentenceModel model = new SentenceModel(modelIn);
            sentenceDetector = new SentenceDetectorME(model);
        } catch (IOException e) {
        }
    }

    public String[] getSentences(String fulltext) {
        return sentenceDetector.sentDetect(fulltext);
    }
    /*------------*/

    private void init() {
        try {
            SentenceParser(sentenceDetectorModelName);
            Tokenizer(tokenizerModelName);
            loadStopWords(stopWordsFileName);
        } catch (FileNotFoundException ex) {
            LOGGER.error("File not found",ex);
        }
    }

    private void iterateThroughDocuments(SolrIndexSearcher searcher, List<String> fields) {
        IndexReader reader = searcher.getIndexReader();


        Bits liveDocs = MultiFields.getLiveDocs(reader); //WARNING: returns null if there are no deletions

        int docnum = 0;

        for (int docID = 0; docID < reader.maxDoc(); docID++) {
            if (liveDocs != null && !liveDocs.get(docID)) {
                continue;               //deleted
            }

            StringBuilder text = new StringBuilder();

            for (String field : fields) {
                try {
                    text.append("  " + reader.document(docID).get(field));
                } catch (IOException ex) {
                    LOGGER.warn("Document " + docID + " missing requested field (" + field + ")...ignoring");
                }
            }
            if (text.length() > 0) { //might as well see if its empty
                processText(text.toString().toLowerCase());
                docnum++;
            }
        }

        LOGGER.info("Number of documents: \t" + docnum);
        for (int zz = 0; zz < counts.length; zz++) {
            LOGGER.info("NUMBER OF " + zz + "\t" + counts[zz]);
        }
    }

    public SuggesterTreeHolder getSuggester() {
        return suggester;
    }

    SuggeterDataStructureBuilder(SolrIndexSearcher searcher, List<String> fields) {
        init();
        iterateThroughDocuments(searcher, fields);
        computeNormalizers();
    }

    private void processText(String text) {
        HashSet<String> seenTerms = new HashSet<String>();
        for (String sentence : getSentences(text)) {
            String[] tokens = getTokens(sentence.replaceAll("[^A-Za-z0-9 ]", " ")); //HACK!!
            for (int zz = 0; zz < tokens.length; zz++) {
                TrieNode tokenNode = suggester.AddString(tokens[zz]);
                counts[0]++;
                Double count = tokenNode.phrases.containsKey(tokens[zz]) ? tokenNode.phrases.get(tokens[zz]) : 0.1;
                tokenNode.phrases.put(tokens[zz], count + 1);

                tokenNode.termfreq++;

                if (!seenTerms.contains(tokens[zz])) {
                    tokenNode.docfreq++;
                    seenTerms.add(tokens[zz]);
                }

                int numterms = 1;
                int yy = zz;
                StringBuilder sb = new StringBuilder();
                sb.append(tokens[zz]);
                while (true) {
                    yy++;
                    if (yy >= tokens.length) { // no other tokens in stream...
                        break;
                    }

                    sb.append(" " + tokens[yy]);
                    //LOGGER.info(numterms+"\t"+sb);
                    if (stopwords.contains(tokens[yy])) { //president of
                        continue;
                    }

                    numterms++;
                    counts[numterms - 1]++;
                    double rightplace = numterms / 10.0;
                    String gram = sb.toString();
                    Double lcount = tokenNode.phrases.containsKey(gram) ? tokenNode.phrases.get(gram) : rightplace;
                    tokenNode.phrases.put(gram, lcount + 1);
                    if (numterms >= NGRAMS) {
                        break;
                    }

                }
            }
        }
    }

    private void loadStopWords(String stopWordsFileName) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader((getClass().getResourceAsStream("/"+stopWordsFileName))));
            String line;
            while ((line = in.readLine()) != null) {
                stopwords.add(line.trim().toLowerCase());
            }
            in.close();
        } catch (Exception ex) {
            LOGGER.error("Error loading stopwords");
        }
    }

    private void computeNormalizers() {
        suggester.computeNormalizers();
    }

    /*private void doSuggests() {
        IndexSearcher searcher = null;// new IndexSearcher(reader);
        for (String testquery : testqueries) {
            LOGGER.info(testquery + "\n-------\n");
            SuggestionResultSet suggestions = suggester.getSuggestions(searcher, getTokens(testquery), field);
            int numout = 0;
            for (SuggestionResult suggestion : suggestions.suggestions) {
                LOGGER.info(suggestion.suggestion + "\t" + suggestion.probability);
                numout++;
                if (numout > 10) {
                    break;
                }
            }
            LOGGER.info("\n\n");
        }

    }*/


}
