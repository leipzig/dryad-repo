/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate;

import java.io.IOException;
import java.net.URL;
import java.net.MalformedURLException;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import org.dspace.content.DCValue;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.identifier.IdentifierService;
import org.dspace.identifier.IdentifierNotFoundException;
import org.dspace.identifier.IdentifierNotResolvableException;
import org.dspace.utils.DSpace;
import org.dspace.authority.orcid.xml.XMLtoBio;
import org.dspace.authority.orcid.model.Bio;
import org.dspace.authority.orcid.model.BioName;

import org.apache.log4j.Logger;

/**
 * OdinsHamr helps users reconcile author metadata with metadata stored in ORCID.
 *
 * Input: a single DSpace item OR a collection
 * Output: CSV file that maps author names in the DSpace item to author names in ORCID.
 * @author Ryan Scherle
 */
@Suspendable
public class OdinsHamr extends AbstractCurationTask {

    private static final String ORCID_QUERY_BASE = "http://pub.orcid.org/search/orcid-bio?q=digital-object-ids:";
    private static final double MATCH_THRESHHOLD = 0.5;
    
    private static Logger log = Logger.getLogger(OdinsHamr.class);
    private IdentifierService identifierService = null;
    DocumentBuilderFactory dbf = null;
    DocumentBuilder docb = null;
    static long total = 0;
    private Context context;

    @Override 
    public void init(Curator curator, String taskId) throws IOException {
        super.init(curator, taskId);
	
        identifierService = new DSpace().getSingletonService(IdentifierService.class);            
	
	    // init xml processing
        try {
            dbf = DocumentBuilderFactory.newInstance();
            docb = dbf.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IOException("unable to initiate xml processor", e);
        }
    }
    
    /**
     * Perform the curation task upon passed DSO
     *
     *	input: an item
     *  output: several lines of authors, can show all or only identified matches
     *
     * @param dso the DSpace object
     * @throws IOException
     */
    @Override
    public int perform(DSpaceObject dso) throws IOException {
        log.info("performing ODIN's Hamr task " + total++ );

        String handle = "\"[no handle found]\"";
        String itemDOI = "\"[no item DOI found]\"";
        String articleDOI = "";

        try {
            context = new Context();
            } catch (SQLException e) {
            log.fatal("Unable to open database connection", e);
            return Curator.CURATE_FAIL;
        }

        if (dso.getType() == Constants.COLLECTION) {
            // output headers for the CSV file that will be created by processing all items in this collection
            report("itemDOI, articleDOI, orcidID, orcidName, dspaceORCID, dspaceName");
        } else if (dso.getType() == Constants.ITEM) {
            Item item = (Item)dso;

            try {
                handle = item.getHandle();
                log.info("handle = " + handle);

                if (handle == null) {
                    // this item is still in workflow - no handle assigned
                    context.abort();
                    return Curator.CURATE_SKIP;
                }

                // item DOI
                DCValue[] vals = item.getMetadata("dc.identifier");
                if (vals.length == 0) {
                    setResult("Object has no dc.identifier available " + handle);
                    log.error("Skipping -- no dc.identifier available for " + handle);
                    context.abort();
                    return Curator.CURATE_SKIP;
                } else {
                    for(int i = 0; i < vals.length; i++) {
                        if (vals[i].value.startsWith("doi:")) {
                            itemDOI = vals[i].value;
                        }
                    }
                }
                log.debug("itemDOI = " + itemDOI);

                // article DOI
                vals = item.getMetadata("dc.relation.isreferencedby");
                if (vals.length == 0) {
                    log.debug("Object has no articleDOI (dc.relation.isreferencedby) " + handle);
                    articleDOI = "";
                } else {
                    articleDOI = vals[0].value;
                }
                    log.debug("articleDOI = " + articleDOI);


                // DSpace names
                List<DCValue> dspaceBios = new ArrayList<DCValue>();
                vals = item.getMetadata("dc.contributor.author");
                if (vals.length == 0) {
                    log.error("Object has no dc.contributor.author available in DSpace" + handle);
                } else {
                    for(int i = 0; i < vals.length; i++) {
                        dspaceBios.add(vals[i]);
                    }
                }

                // ORCID names
                List<Bio> orcidBios = retrieveOrcids(itemDOI);

                if(articleDOI != null && articleDOI.length() > 0) {
                    List<Bio> articleBios = retrieveOrcids(articleDOI);
                    for(Bio bio : articleBios) {
                        if(!containsName(orcidBios, bio)) {
                            orcidBios.add(bio);
                        }
                    }
                }

                // reconcile names between DSpace and ORCID
                HashMap<DCValue,Bio> mappedNames = doHamrMatch(dspaceBios, orcidBios);

                // output the resultant mappings
                Iterator nameIt = mappedNames.entrySet().iterator();
                List<DCValue> authors = new ArrayList<DCValue>();

                while(nameIt.hasNext()) {
                    Map.Entry pairs = (Map.Entry)nameIt.next();
                    Bio mappedOrcidEntry = (Bio)pairs.getValue();
                    DCValue mappedDSpaceDCVal = (DCValue)pairs.getKey();
                    Bio mappedDSpaceEntry = createBio("", mappedDSpaceDCVal.value);
                    report(itemDOI + ", " + articleDOI + ", " + mappedOrcidEntry.getOrcid() + ", \"" + getName(mappedOrcidEntry) + "\", " +
                       mappedDSpaceEntry.getOrcid() + ", \"" + getName(mappedDSpaceEntry) + "\", " + hamrScore(mappedDSpaceEntry,mappedOrcidEntry));

                    // if hamrScore is greater or = to 0.7, then add this to new metadata:

                    DCValue authorMetadata = mappedDSpaceDCVal.copy();
                    if (hamrScore(mappedDSpaceEntry,mappedOrcidEntry) >= 0.7) {
                        authorMetadata.authority = mappedOrcidEntry.getOrcid();
                        authorMetadata.confidence = 500;
                    }
                    authors.add(authorMetadata);
                    setResult("Last processed item = " + handle + " -- " + itemDOI);
                }

                item.clearMetadata("dc","contributor","author",null);
                for (DCValue auth : authors) {
                    item.addMetadata("dc", "contributor", "author", null, auth.value, auth.authority, auth.confidence);
                }
                item.update();
                log.info(handle + " done.");
            } catch (Exception e) {
                log.fatal("Skipping -- Exception in processing " + handle, e);
                setResult("Object has a fatal error: " + handle + "\n" + e.getMessage());
                report("Object has a fatal error: " + handle + "\n" + e.getMessage());

                context.abort();
                return Curator.CURATE_SKIP;
            }
        } else {
            log.info("Skipping -- non-item DSpace object");
            setResult("Object skipped (not an item)");
            context.abort();
            return Curator.CURATE_SKIP;
            }

        try {
            context.complete();
            } catch (SQLException e) {
            log.fatal("Unable to close database connection", e);
        }

        log.info("ODIN's Hamr complete");
        return Curator.CURATE_SUCCESS;
	}

    /**
       Computes a minimum between three integers.
    **/
    private static int minimum(int a, int b, int c) {
	    return Math.min(Math.min(a, b), c);
    }

    /**
       Levenshtein distance algorithm, borrowed from
       http://en.wikibooks.org/wiki/Algorithm_Implementation/Strings/Levenshtein_distance
    **/
    public static int computeLevenshteinDistance(CharSequence str1, CharSequence str2) {
        int[][] distance = new int[str1.length() + 1][str2.length() + 1];

        for (int i = 0; i <= str1.length(); i++)
            distance[i][0] = i;
        for (int j = 1; j <= str2.length(); j++)
            distance[0][j] = j;

        for (int i = 1; i <= str1.length(); i++)
            for (int j = 1; j <= str2.length(); j++)
            distance[i][j] = minimum(
                         distance[i - 1][j] + 1,
                         distance[i][j - 1] + 1,
                         distance[i - 1][j - 1] + ((str1.charAt(i - 1) == str2.charAt(j - 1)) ? 0 : 1));

        return distance[str1.length()][str2.length()];
    }
    
    /**
       Scores the correspondence between two author names.
    **/
    private double hamrScore(Bio bio1, Bio bio2) {
        // if the orcid ID's match, the names are a perfect match
        if(bio1.getOrcid() != null && bio1.getOrcid().equals(bio2.getOrcid())) {
            return 1.0;
        }

        int maxlen = Math.max(getName(bio1).length(), getName(bio2).length());
        int editlen = computeLevenshteinDistance(getName(bio1), getName(bio2));

        return (double)(maxlen-editlen)/(double)maxlen;
    }
    
    /**
       Matches two lists of Orcid names using the Hamr algorithm.
    **/
    private HashMap<DCValue,Bio> doHamrMatch(List<DCValue>dspaceBios, List<Bio>orcidBios) {
	HashMap<DCValue,Bio> matchedNames = new HashMap<DCValue,Bio>();

	// for each dspaceName, find the best possible match in the orcidBios list, provided the score is over the threshhold
	for(DCValue dspaceData:dspaceBios) {
        Bio dspaceBio = createBio("",dspaceData.value);
	    double currentScore = 0.0;
	    Bio currentMatch = null;
	    for(Bio orcidBio:orcidBios) {
            double strength = hamrScore(dspaceBio, orcidBio);
            if(strength > currentScore && strength > MATCH_THRESHHOLD) {
                currentScore = strength;
                currentMatch = orcidBio;
            }
	    }
	    if(currentScore > 0.0) {
		    matchedNames.put(dspaceData, currentMatch);
	    }
	}

	return matchedNames;
    }
    
    /**
       Reports whether a given targetName appears in a list of Orcid Bios.
    **/
    private boolean containsName(List<Bio> bioList, Bio targetBio) {
        if(bioList != null) {
            for(Bio bio:bioList) {
                if(getName(bio).equals(getName(targetBio))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Bio createBio (String orcid, String dryadName) {
        Bio bio = new Bio();
        bio.setOrcid(orcid);

        // Create a Pattern object
        Pattern lastfirst = Pattern.compile("(.*),\\s*(.*)");

        // Now create matcher object.
        Matcher m = lastfirst.matcher(dryadName);
        if (m.find()) {
            bio.setName(new BioName(m.group(2), m.group(1), "", null));
        } else {
            log.error("Name " + dryadName + " is not in lastname, firstname format.");
        }
        return bio;
    }

    /**
       Retrieve a list of names associated with this DOI in ORCID.
       The DOI may represent a Dryad item, or any other work.
    **/
    private List<Bio> retrieveOrcids(String aDOI) {
        List<Bio> orcidBios = new ArrayList<Bio>();

        if(aDOI.startsWith("doi:")) {
            aDOI = aDOI.substring("doi:".length());
        }
        try {
            URL orcidQuery = new URL(ORCID_QUERY_BASE + "%22" + aDOI + "%22");
            Document orcidDoc = docb.parse(orcidQuery.openStream());
            XMLtoBio converter = new XMLtoBio();
            return orcidBios = converter.convert(orcidDoc);
        } catch (MalformedURLException e) {
            log.error("cannot make a valid URL for aDOI="  + aDOI, e);
        } catch (IOException e) {
            log.error("IO problem for aDOI="  + aDOI, e);
        } catch (SAXException e) {
            log.error("error processing XML for aDOI="  + aDOI, e);
        }

        return orcidBios;
    }

    private String getName(Bio bio) {
        return bio.getName().getFamilyName() + ", " + bio.getName().getGivenNames();
    }
}
