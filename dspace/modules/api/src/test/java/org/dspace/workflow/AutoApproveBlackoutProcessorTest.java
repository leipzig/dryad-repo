/*
 */
package org.dspace.workflow;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import org.apache.log4j.Logger;
import org.datadryad.api.DryadDataPackage;
import org.datadryad.test.ContextUnitTest;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author Dan Leehr <dan.leehr@nescent.org>
 */
public class AutoApproveBlackoutProcessorTest extends ContextUnitTest {
    private static Logger log = Logger.getLogger(AutoApproveBlackoutProcessorTest.class);
    private Calendar calendar;
    private Date pastDate;
    private Date nowDate;
    private Date futureDate;

    @Before
    public void setUp() {
        super.setUp();
        calendar = new GregorianCalendar();
        nowDate = calendar.getTime();
        int currentYear = calendar.get(Calendar.YEAR);
        calendar.set(Calendar.YEAR, currentYear - 1);
        pastDate = calendar.getTime();
        calendar.set(Calendar.YEAR, currentYear + 1);
        futureDate = calendar.getTime();
    }

    @Test
    public void testApproveFromBlackoutWithPastDate() throws Exception {
        // test that an item in blackout can be approved
        DryadDataPackage dataPackage = DryadDataPackage.createInWorkflow(context);
        AutoCurateToBlackoutProcessor curateToBlackoutProcessor = new AutoCurateToBlackoutProcessor(context);
        Boolean curatedToBlackout = curateToBlackoutProcessor.processWorkflowItem(dataPackage.getWorkflowItem(context));
        assertTrue("Could not curate new item to blackout", curatedToBlackout);

        // Now, item is in blackout, test that it can be approved
        dataPackage.setBlackoutUntilDate(pastDate); // Set the blackoutUntil date to be the past
        AutoApproveBlackoutProcessor approveBlackoutProcessor = new AutoApproveBlackoutProcessor(context);
        Boolean approvedFromBlackout = approveBlackoutProcessor.processWorkflowItem(dataPackage.getWorkflowItem(context));
        assertTrue("Could not approve item from blackout to archive", approvedFromBlackout);

        Boolean archived = dataPackage.getItem().isArchived();
        assertTrue("Item should be archived", archived);
    }

    /**
     * Test of getActionID method, of class AutoApproveBlackoutProcessor.
     */
    @Test
    public void testGetActionID() {
        log.info("getActionID");
        AutoApproveBlackoutProcessor instance = new AutoApproveBlackoutProcessor(context);
        String expResult = "afterPublicationAction";
        String result = instance.getActionID();
        assertEquals("Blackout processor's action mismatch", expResult, result);
    }

}
