/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.xmlui.aspect.dryadwidgets;

import java.util.Map;
import org.apache.avalon.framework.logger.AbstractLogEnabled;
import org.apache.avalon.framework.parameters.Parameters;
import org.apache.cocoon.selection.Selector;
import org.apache.log4j.Logger;
import org.dspace.app.xmlui.utils.ContextUtil;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.identifier.DOIIdentifierProvider;
import org.dspace.identifier.IdentifierNotFoundException;
import org.dspace.identifier.IdentifierNotResolvableException;
import org.dspace.utils.DSpace;
import org.dspace.workflow.WorkflowItem;

/**
 * This simple selector attempts to resolve a DOI passed in as the pattern.  It
 * returns true if the identifier is found, false if not.
 * Can be used to determine which widget banner to render.
 *
 * The publisher parameter must be provided, but is not recorded.
 * 
 * @author Dan Leehr
 */

public class WidgetBannerSelector extends AbstractLogEnabled implements
        Selector
{

    private static Logger log = Logger.getLogger(WidgetBannerSelector.class);

    /**
     * Determine if the provided identifier is resolvable
     */
    public boolean select(String expression, Map objectModel,
            Parameters parameters) {
        try
        {

            Context context = ContextUtil.obtainContext(objectModel);
            String publisher = parameters.getParameter("publisher","");
            if(publisher.length() == 0 || expression.length() == 0) {
                return false;
            }

            // incoming identifier should be a DOI, try to resolve with DOIIdentifierPRovider
            DSpaceObject dso = null;
            DOIIdentifierProvider doiService = new DSpace().getSingletonService(DOIIdentifierProvider.class);
            try {
                dso = doiService.resolve(context, expression,  new String[]{});
            } catch (IdentifierNotFoundException ex) {
                // ignoring the exception, leaving dso as null
            } catch (IdentifierNotResolvableException ex) {
                // ignoring the exception, leave dso as null
            }

            if (dso != null && dso.getType() == Constants.ITEM) {
                Item item = (Item)dso;
                // need to check if item is in workflow or workspace
                WorkflowItem wfi = WorkflowItem.findByItemId(context, item.getID());
                WorkspaceItem wsi = WorkspaceItem.findByItem(context, item);

                if(wfi == null && wsi == null) {
                    // Item is found and not in workspace, return the banner
                    return true;
                } else {
                    // Item is either in the workflow or workspace, do not return the banner
                    return false;
                }
            } else {
                return false;
            }
        }
        catch (Exception e)
        {
            // Log it and returned no match.
            log.error("Error selecting based on provided identifier " +
                    expression + " : " + e.getMessage());
            return false;
        }
    }

}
