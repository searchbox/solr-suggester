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
    private HashSet<String> stopwords;
    public int NGRAMS;
    public int numdocs;
    public int counts[];
    private SuggesterTreeHolder suggester;

    /*------------*/
    public void Tokenizer(String filename_model) throws FileNotFoundException {
        InputStream modelIn = (getClass().getResourceAsStream("/" + filename_model));
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
        InputStream modelIn = (getClass().getResourceAsStream("/" + filename_model));
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
            LOGGER.error("File not found", ex);
        }
    }

    private void iterateThroughDocuments(SolrIndexSearcher searcher, List<String> fields) {
        IndexReader reader = searcher.getIndexReader();
        Bits liveDocs = MultiFields.getLiveDocs(reader); //WARNING: returns null if there are no deletions

        for (int docID = 0; docID < reader.maxDoc(); docID++) {
            if (liveDocs != null && !liveDocs.get(docID)) {
                continue;               //deleted
            }

            if ((docID % 1000) == 0) {
                LOGGER.debug("Doing " + docID + " of " + reader.maxDoc());
            }

            StringBuilder text = new StringBuilder();
            for (String field : fields) {       //not sure if this is the best way, might make sense to do a 
                //process text for each field individually, but then book keeping 
                //the doc freq for terms becomes a bit of a pain in the ass
                try {
                    text.append(". " + reader.document(docID).get(field));
                } catch (IOException ex) {
                    LOGGER.warn("Document " + docID + " missing requested field (" + field + ")...ignoring");
                }
            }
            if (text.length() > 0) { //might as well see if its empty
                processText(text.toString().toLowerCase());
                numdocs++;
            }
        }

        LOGGER.info("Number of documents analyzed: \t" + numdocs);
        for (int zz = 0; zz < counts.length; zz++) {
            LOGGER.debug("Number of " + zz + "-grams: \t" + counts[zz]);
        }
    }

    public SuggesterTreeHolder getSuggester() {
        return suggester;
    }

    SuggeterDataStructureBuilder(SolrIndexSearcher searcher, List<String> fields, int ngrams, int minDocFreq, int minTermFreq) {
        NGRAMS = ngrams;
        counts = new int[NGRAMS];
        suggester = new SuggesterTreeHolder(NGRAMS);

        init();
        iterateThroughDocuments(searcher, fields);
        computeNormalizers(minDocFreq, minTermFreq);
    }

    private void processText(String text) {
        LOGGER.trace("Processing text:\t" + text);
        HashSet<String> seenTerms = new HashSet<String>();
        for (String sentence : getSentences(text)) {
            String[] tokens = getTokens(sentence.replaceAll("[^A-Za-z0-9 ]", " ")); //TODO: fix this part, its a bit of a hack but should be okay
            for (int zz = 0; zz < tokens.length; zz++) {
                if (stopwords.contains(tokens[zz])) {     //TODO: should do a skip gram, but we'll look into that later SBSUGGEST-3
                    continue;
                }
                TrieNode tokenNode = suggester.AddString(tokens[zz]);
                counts[0]++;
                
                tokenNode.AddPhraseIncrementCount(tokens[zz], .1);
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
                    tokenNode.AddPhraseIncrementCount(gram, rightplace);
                    if (numterms >= NGRAMS) {
                        break;
                    }

                }
            }
        }
    }

    private void loadStopWords(String stopWordsFileName) {
        stopwords = new HashSet<String>();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader((getClass().getResourceAsStream("/" + stopWordsFileName))));
            String line;
            while ((line = in.readLine()) != null) {
                stopwords.add(line.trim().toLowerCase());
            }
            in.close();
        } catch (Exception ex) {
            LOGGER.error("Error loading stopwords\t" + ex.getMessage());
        }
    }

    private void computeNormalizers(int minDocFreq, int minTermFreq) {
        suggester.computeNormalizers(numdocs, minDocFreq, minTermFreq);
    }
}
