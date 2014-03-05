Solr-Suggester by Searchbox
=========
The Solr-Suggester plugin by Searchbox is a Solr Search component 
which adds add out-of-the-box high quality Suggestions to search queries. 

The Solr-Suggester plugin analyses the designated full-text fields
and creates a suggestion model which holds a list of possible phrase 
completions and their associated probability. When a query is submitted,
the search component quickly (< 50ms) identifies phrases which could
be a completion for the query with the highest probability and returns
them in the Solr response. We have taken great care to ensure that
the parameters and workflow are very similar to the Solr Suggester so
that a low learning curve and rapid installation are possible.

Getting Started
---------------
Add the following request handler to the appropriate solrconfig.xml:

```xml
	<searchComponent class="com.searchbox.SuggesterComponent" name="sbsuggest">
        <lst name="fields">
            <str name="field">article-title</str>
			<str name="field">article-abstract</str>
        </lst>
        <int name="ngrams">3</int>
        <str name="buildOnCommit">true</str>
		<str name="buildOnOptimize">true</str>
        <str name="storeDir">sbsuggest</str>
		<int name="maxNumDocs">50000</int>
		<int name="minDocFreq">2</int>
		<int name="minTermFreq">2</int>
	</searchComponent>
```

We will explain the meaning and usage of the various parameters:

```xml
	<lst name="fields">
		<str name="field">article-title</str>
		<str name="field">article-abstract</str>
	</lst>
```

We need to define the schema fields which will be used for the creation of the possible
phrase suggestions. Analysis of these fields requires that they are Stored=true so that 
the raw full text is available. The list can contain one or more fields.

```xml
	<int name="ngrams">3</int>
```

This parameter specifies the maximum number of words which could possibly used for
a phrase completion. For example the possible phrases for various sizes are as follows:

1="child" 2="child eats" 3="child eats food" 4="child eats food tomorrow".    

Typically this value is set to 3. Higher values can be used but the memory and resources
required to produce the necessary files grows exponentially, so beware!

```xml
	<str name="buildOnCommit">true</str>
	<str name="buildOnOptimize">true</str>
```

These options define if the suggester model should be (re)build upon a solr commit or
upon optimize. This works very similar to the Solr Suggester plugin and
thus their comments hold: Building on commit is very expensive and is discouraged for most 
production systems.  For large indexes, one commit may take minutes since the building of 
suggester model is single threaded. Typically one uses buildOnOptimize or explicit build instead.

```xml
	<str name="storeDir">sbsuggest</str>
```

This is the directory where the serialied model will be stored. It can be either an aboslute
directory (starting with "/") or a relative directory to Solr's data directory. If the
path doesn't exist, the necessary directories are created so it is valid.

```xml
	<int name="maxNumDocs">50000</int>
```

The maximum number of doucments to analyze to produce the suggestion model. If set to -1, then
all documents in the repository are used. The higher the number the more resources and time
are required to compute and store. Using 150,000 documents requires around 8GB of memory
for computation (much less for storage and actual usage).
	
```xml
	<int name="minDocFreq">2</int>
	<int name="minTermFreq">2</int>
```

These two parameters put a limit on the phrases which are considered acceptable. In this
case we specify that a phrase must appear in at least 2 documents and must appear in total
at least twice. The higher the number, the less phrases will be modeled and thus require
notably less processing time and resources. Considering that the suggestion is a probabilistic
model, if it is known that there are many phrases which appear very infrequently and
don't have a high recall value, putting these numbers higher will result in gained performance.
The default is 2 for each and gives very well represented results.
			
Usage Of Search Component
---------------
Although search components are intended to be used in line with searchers, it is 
possible to define a request handler for example purposes (similar to the default
installation of Solr and their demonstration of the spell checker). To do this
simply add to solrconfig.xml:

```xml
	<requestHandler name="/sbsuggest" class="solr.SearchHandler">
		<arr name="last-components">
			<str>sbsuggest</str>
		</arr>
	</requestHandler>
```

The following are acceptable URL (and thus configuration) options which
can be set:

```xml
	sbsuggester.build=true
```

THIS IS REQUIRED upon first running to create the model, especially if buildOnOptimize
and buildOnCommit are not set to true. If this option isn't sent with the first
request there will be no model to process the query. This also throws an error
in the Solr Log. Given the size of the repository and processor power of the machines
this can take from a few seconds to a few minutes.

```xml
    sbsuggester.q
```

Similar to the Solr spell checker, this parameter overrides the common q parameter, thus
we make a suggestion based on this field if it is set, otherwise we make a suggestion
based on the standard q="...." 
	
```xml
    sbsuggester.count
```

Defines the number of possible suggestions to return. The default is 5

```xml
    sbsuggester.mps 
```

Defines the number of possible phrases to analyze, Default is 100. Since suggesters require
a very high throughout, it isn't efficient to consider all possible phrases so we can limit
the search to the 100 most likely phrases and sort them. In general, 100 seems to be
perfectly reasonable. Feel free to lower the number for greater performance.


Understanding the results
---------------

A sample response to the following query is presented below:

http://192.168.56.101:8982/pubmed/sbsuggest?q=prot&wt=xml

```xml
<response>
	<lst name="responseHeader">
		<int name="status">0</int>
		<int name="QTime">21</int>
	</lst>
	<result name="response" numFound="0" start="0"/>
	<lst name="sbsuggester">
		<double name="protein">0.00775645219590898</double>
		<double name="proteins">0.005854042756679594</double>
		<double name="protein expression">0.0030364518693309604</double>
		<double name="protein protein interactions">0.002501343263734827</double>
		<double name="protein protein interaction">0.0018614647544073133</double>
		<double name="protein coding genes">0.0017451232072568563</double>
	</lst>
</response>
```

We can see that we have asked for suggestions to the word "pro" using a
pubmed dataset (a medical research publication repository). As expected
we see see a list of responses in the sbsuggester xml object. The name
of the entity is associated with its probabilistic score. Of course since
there are many possible suggestions which begin with "pro", the probabilities
will typically be very small, but of greater importance is the order.
The responses which appear higher in the list are of greater chance (and
therefore quality) as a suggestion to the query.

Enjoy!

License
=======
Searchbox is distributed under the Apache 2 license. Please keep the existing headers.

Attribution
======
Main developer
- Andrew Janowczyk - <andrew.janowczyk@searchbox.com>, @andrew, http://www.searchbox.com