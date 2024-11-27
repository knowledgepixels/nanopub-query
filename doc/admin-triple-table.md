<!DOCTYPE html>
<html lang=en>
<head>
<title>Admin Triple Table</title>
<meta charset="utf-8">
<link rel="stylesheet" href="/style.css">
</head>
<body>
<table>
<tr><th>Subject</th><th>Predicate</th><th>Object</th><th>Graph</th><th>Group</th><th>Comment</th></tr>
<tr><td>NANOPUB</td><td>npa:hasValidSignatureForPublicKey</td><td>FULL_PUBKEY</td><td>npa:graph</td><td>meta</td><td>full pubkey if signature is valid</td></tr>
<tr><td>NANOPUB</td><td>npa:hasValidSignatureForPublicKeyHash</td><td>PUBKEY_HASH</td><td>npa:graph</td><td>meta</td><td>hex-encoded SHA256 hash if signature is valid</td></tr>
<tr><td>NANOPUB</td><td>npx:signedBy</td><td>SIGNER</td><td>npa:graph</td><td>meta</td><td>ID of signer</td></tr>
<tr><td>NANOPUB1</td><td>RELATION</td><td>NANOPUB2</td><td>npa:networkGraph</td><td>meta</td><td>any inter-nanopub relation found in NANOPUB1</td></tr>
<tr><td>NANOPUB</td><td>npx:introduces</td><td>THING</td><td>npa:graph</td><td>meta</td><td>when such a triple is present in pubinfo of NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>npx:describes</td><td>THING</td><td>npa:graph</td><td>meta</td><td>when such a triple is present in pubinfo of NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>npx:hasSubIri</td><td>SUB_IRI</td><td>npa:graph</td><td>meta</td><td>for any IRI minted in the namespace of the NANOPUB</td></tr>
<tr><td>NANOPUB1</td><td>npa:refersToNanopub</td><td>NANOPUB2</td><td>npa:networkGraph</td><td>meta</td><td>generic inter-nanopub relation</td></tr>
<tr><td>NANOPUB</td><td>npx:invalidates</td><td>INVALIDATED_NANOPUB</td><td>npa:graph</td><td>meta</td><td>if the NANOPUB retracts or supersedes another nanopub</td></tr>
<tr><td>NANOPUB</td><td>npx:retracts</td><td>RETRACTED_NANOPUB</td><td>npa:graph</td><td>meta</td><td>if the NANOPUB retracts another nanopub</td></tr>
<tr><td>NANOPUB</td><td>npx:supersedes</td><td>SUPERSEDED_NANOPUB</td><td>npa:graph</td><td>meta</td><td>if the NANOPUB supersedes another nanopub</td></tr>
<tr><td>NANOPUB</td><td>npa:hasHeadGraph</td><td>HEAD_GRAPH</td><td>npa:graph</td><td>meta</td><td>direct link to the head graph of the NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>npa:hasGraph</td><td>GRAPH</td><td>npa:graph</td><td>meta</td><td>generic link to all four graphs of the given NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>np:hasAssertion</td><td>ASSERTION_GRAPH</td><td>npa:graph</td><td>meta</td><td>direct link to the assertion graph of the NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>np:hasProvenance</td><td>PROVENANCE_GRAPH</td><td>npa:graph</td><td>meta</td><td>direct link to the provenance graph of the NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>np:hasPublicationInfo</td><td>PUBINFO_GRAPH</td><td>npa:graph</td><td>meta</td><td>direct link to the pubinfo graph of the NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>npa:artifactCode</td><td>ARTIFACT_CODE</td><td>npa:graph</td><td>meta</td><td>artifact code starting with 'RA...'</td></tr>
<tr><td>NANOPUB</td><td>npa:isIntroductionOf</td><td>AGENT</td><td>npa:graph</td><td>meta</td><td>linking intro nanopub to the agent it is introducing</td></tr>
<tr><td>NANOPUB</td><td>npa:declaresPubkey</td><td>FULL_PUBKEY</td><td>npa:graph</td><td>meta</td><td>full pubkey declared by the given intro NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>dct:created</td><td>CREATION_DATE</td><td>npa:graph</td><td>meta</td><td>normalized creation timestamp</td></tr>
<tr><td>NANOPUB</td><td>npx:hasNanopubType</td><td>NANOPUB_TYPE</td><td>npa:graph</td><td>meta</td><td>type of NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>rdfs:label</td><td>LABEL</td><td>npa:graph</td><td>meta</td><td>label of NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>dct:description</td><td>LABEL</td><td>npa:graph</td><td>meta</td><td>description of NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>dct:creator</td><td>CREATOR</td><td>npa:graph</td><td>meta</td><td>creator of NANOPUB (can be several)</td></tr>
<tr><td>NANOPUB</td><td>pav:authoredBy</td><td>AUTHOR</td><td>npa:graph</td><td>meta</td><td>author of NANOPUB (can be several)</td></tr>
<tr><td>NANOPUB</td><td>npa:hasFilterLiteral</td><td>FILTER_LITERAL</td><td>npa:graph</td><td>literal</td><td>auxiliary literal for filtering by type and pubkey in text repo</td></tr>
<tr><td>REPO</td><td>npa:hasNanpubCount</td><td>NANOPUB_COUNT</td><td>npa:graph</td><td>admin</td><td>number of nanopubs loaded (BUG: npa:hasNanpubCount should be npa:hasNanopubCount)</td></tr>
<tr><td>REPO</td><td>npa:hasNanopubChecksum</td><td>NANOPUB_CHECKSUM</td><td>npa:graph</td><td>admin</td><td>checksum of all loaded nanopubs (order-independent XOR checksum on trusty URIs in Base64 notation)</td></tr>
<tr><td>NANOPUB</td><td>npa:hasLoadNumber</td><td>LOAD_NUMBER</td><td>npa:graph</td><td>admin</td><td>the sequential number at which this NANOPUB was loaded</td></tr>
<tr><td>NANOPUB</td><td>npa:hasLoadChecksum</td><td>LOAD_CHECKSUM</td><td>npa:graph</td><td>admin</td><td>the checksum of all loaded nanopubs after loading the given NANOPUB</td></tr>
<tr><td>NANOPUB</td><td>npa:hasLoadTimestamp</td><td>LOAD_TIMESTAMP</td><td>npa:graph</td><td>admin</td><td>the time point at which this NANOPUB was loaded</td></tr>
</table>
</body>
</html>
