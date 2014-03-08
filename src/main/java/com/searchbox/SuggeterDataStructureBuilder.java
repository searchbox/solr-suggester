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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
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
    private HashSet<String> stopwords;
    private Analyzer analyzer;
    private String[] fields;
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

//    public String[] getTokens(String fulltext) {
//        return tokenizer.tokenize(fulltext);
//    }
    /*------------*/

     private String[] getTokens(String fulltext) { //break text into tokens using opennlp model
         LinkedList<String> tokens = new LinkedList<String>();
        try {
            TokenStream tokenStream = analyzer.tokenStream(fields[0], new StringReader(fulltext));
            tokenStream.reset();
            CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);

            while (tokenStream.incrementToken()) {
                String token = charTermAttribute.toString();
                tokens.add(token);
            }

        } catch (IOException ex) {
            LOGGER.error("Failure reading tokens from stream", ex);
        }
        return tokens.toArray(new String[0]);
    }
    
    /*------------*/
    public void SentenceParser(String filename_model) throws FileNotFoundException {
        InputStream modelIn = (getClass().getResourceAsStream("/" + filename_model));
        try {
            SentenceModel model = new SentenceModel(modelIn);
            sentenceDetector = new SentenceDetectorME(model);
        } catch (IOException e) {
        }
    }

    public String[] getSentences(String fulltext) { //break text into sentences
        return sentenceDetector.sentDetect(fulltext);
    }
    /*------------*/

    private void init() {
        try {
            SentenceParser(sentenceDetectorModelName);
            Tokenizer(tokenizerModelName);
        } catch (FileNotFoundException ex) {
            LOGGER.error("File not found", ex);
        }
    }

    private void iterateThroughDocuments(SolrIndexSearcher searcher, String [] fields, int maxNumDocs) {
        IndexReader reader = searcher.getIndexReader();
        Bits liveDocs = MultiFields.getLiveDocs(reader); //WARNING: returns null if there are no deletions


        maxNumDocs = Math.min(maxNumDocs, reader.maxDoc());

        if (maxNumDocs == -1) {
            maxNumDocs = reader.maxDoc();
        }
        LOGGER.info("Analyzing docs:\t" + numdocs);

        for (int docID = 0; docID < reader.maxDoc(); docID++) {
            if (numdocs > maxNumDocs) {
                break;
            }
            if (liveDocs != null && !liveDocs.get(docID)) {
                continue;               //deleted
            }

            if ((docID % 1000) == 0) {
                LOGGER.debug("Doing " + docID + " of " + maxNumDocs);
            }

            StringBuilder text = new StringBuilder();
            for (String field : fields) {       //not sure if this is the best way, might make sense to do a 
                //process text for each field individually, but then book keeping 
                //the doc freq for terms becomes a bit of a pain in the ass
                try {
                    IndexableField[] multifield = reader.document(docID).getFields(field);
                    for (IndexableField singlefield : multifield) {
                        text.append(". " + singlefield.stringValue()); //create one big string from all of the text in the documents for processing later on
                    }


                } catch (IOException ex) {
                    LOGGER.warn("Document " + docID + " missing requested field (" + field + ")...ignoring");
                }
            }
            if (text.length() > 0) { //might as well see if its empty
                processText(text.toString().toLowerCase()); //actually processes the massive string which was created from all of the above fields
                numdocs++;
            }
        }

        LOGGER.info("Number of documents analyzed: \t" + numdocs);
        for (int zz = 0; zz < counts.length; zz++) {
            LOGGER.info("Number of " + zz + "-grams: \t" + counts[zz]);
        }
    }

    public SuggesterTreeHolder getSuggester() {
        return suggester;
    }

    SuggeterDataStructureBuilder(SolrIndexSearcher searcher, String [] fields, int ngrams, int minDocFreq, int minTermFreq, int maxNumDocs, String nonpruneFileName, List<String> stopWords) {
        NGRAMS = ngrams;
        counts = new int[NGRAMS];
        suggester = new SuggesterTreeHolder(NGRAMS, nonpruneFileName);
        // Solr 4.4 method change
        analyzer= searcher.getCore().getLatestSchema().getAnalyzer();
        //analyzer= searcher.getCore().getSchema().getAnalyzer();
        this.stopwords = new HashSet<String>(stopWords);
        this.fields=fields;
        init();
        iterateThroughDocuments(searcher, fields, maxNumDocs);
        computeNormalizers(minDocFreq, minTermFreq);
    }

    private void processText(String text) {
        LOGGER.trace("Processing text:\t" + text);
        HashSet<String> seenTerms = new HashSet<String>();
        for (String sentence : getSentences(text)) {
            String [] tokens = getTokens(sentence);
            for (int zz = 0; zz < tokens.length; zz++) {  //for each token in the massive string...
                String localtoken = tokens[zz];
                if (stopwords.contains(localtoken)) {     //TODO: should do a skip gram, but we'll look into that later SBSUGGEST-3
                    continue;
                }
                
                TrieNode tokenNode = suggester.AddString(localtoken); //add the string as a possible completion
                counts[0]++;

                tokenNode.AddPhraseIncrementCount(localtoken, .1); // and then add one to its doc count
                tokenNode.termfreq++;

                if (!seenTerms.contains(localtoken)) { //if this token havent been seen in this document already, then add it to this document and increase the document count for this token
                    tokenNode.docfreq++;
                    seenTerms.add(localtoken);
                }

                int numterms = 1;
                int yy = zz;
                StringBuilder sb = new StringBuilder();
                sb.append(localtoken);
                while (true) { //building up possible phrases using skip grams. we want to keep adding tokens while the token is a stop word so that we can get "republic of ireland" instead of "republic of" or "of ireland" as suggestions
                    yy++;
                    if (yy >= tokens.length) { // no other tokens in stream...
                        break;
                    }
                    String localtoken_2 = tokens[yy];
                    sb.append(" " + localtoken_2);
                    //LOGGER.info(numterms+"\t"+sb);
                    if (stopwords.contains(localtoken_2)) { //president of   
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

    

    private void computeNormalizers(int minDocFreq, int minTermFreq) { //since its a probability, we need to normalize overall counts so that they're in the range of 0 to 1
        suggester.computeNormalizers(numdocs, minDocFreq, minTermFreq);
    }
}
