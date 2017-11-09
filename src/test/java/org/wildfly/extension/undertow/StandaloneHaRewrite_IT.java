package org.wildfly.extension.undertow;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

import com.idbs.helper.io.IdbsIO;
import static org.junit.Assert.assertFalse;
import org.junit.Ignore;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import static org.xmlunit.diff.ComparisonResult.DIFFERENT;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;
import org.xmlunit.diff.ElementSelectors;

/**
 * This Integration test will check that when the standalone-ewb-ha.xml file is re-written by jboss it does not lose any
 * of the attributes. This has been seen in a bug where the extension parser added for undertow was not woring
 * correctly.
 *
 * In order to achieve the test we need to read the ha from the file system. Therefore this test assumes that the ITs
 * are being executed in the same file system as the running. In fact further to this restriction it is assumed that the
 * server is deployed into the dir C:\EWB\Deployment\EWB_10.x\WildFly\ewb-server - if this changes the hard coded
 * location for the test will require modification. The location of execution is defined in in the settings.xml (for
 * ITs) under the tag <deploy.directory> + the value of jenkins variable ${deploy.directory.folder}.
 *
 * In addition, the initial reading of the standalone-ewb-ha has to be done very early on when the server is started -
 * otherwise the app server could rewrite the ha file for some/any reason before this test is executed and thus the test
 * will have no value, it would give false positives. To achieve this the integration test jenkins job has been modified
 * to copy the standalone-ha to a file called "standalone-ewb-ha-copyForRewriteTests.xml". If this test is executed
 * locally this file will not be present - a suitable warning will be issued. Copy the standalone-ha file to the
 * expected file will remedy this local failure.
 *
 * @author rnaylor
 *
 */
public class StandaloneHaRewrite_IT
{
    private static final String SERVER_ROOT_DIR = "C:\\EWB\\Deployment\\EWB_10.x\\WildFly\\ewb-server";

    private static final String STANDALONE_HA_ACTUAL_FULLPATH = SERVER_ROOT_DIR
        + "\\standalone\\configuration\\standalone-ewb-ha.xml";

    private static final String STANDALONE_HA_COPY_ACTUAL_FULLPATH = SERVER_ROOT_DIR
        + "\\standalone\\configuration\\standalone-ewb-ha-copyForRewriteTests.xml";

    private static final String SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE = "localOverride-serverRootPath";

    @Test
    @Ignore("See ELNA-793, once this tool is complete then we can remove this test altogether unless someone can think of a reliable/non-fragile way of doing an XML comparison")
    public void haFileRewriteDoesNotChangeFile() throws Exception
    {
        /*
         * This test reads the ha file and then forces a change to the
         */

        // first get the age and content of the ha file as it was before the server had a chance to change anything
        long initialLastModifiedDate = getHaFileLastModifiedDate();

        forceRewrite();

        long modifiedLastModifiedDate = getHaFileLastModifiedDate();

        // first we need to ensure that the ha HAS been rewritten, i.e. the last modified dates are not the same
        assertTrue(
            "the last modified dates for the ha files are not as expected - it has not been re-written. "
                + "\nMay need to increase the 2sec delay in forceRewrite() to allow the .bat file time to execute",
            initialLastModifiedDate < modifiedLastModifiedDate);

        // now check the content of the file... this is a very basic char for char check of file content.
        String msg = "The HA file content is NOT the same. This means that rewrite of the HA by Jboss has changed its content. "
            + "\n********************** THIS IS POTENTIALLY VERY SERIOUS *******************************************"
            + "\nas it implies that attributes have been change during the rewrite. The best way to check this is to "
            + "\n1. take a copy of the a newly deployed server HA file before starting the server."
            + "\n2. start the server and force a rewrite of the HA file using the jboss-cli.bat utility (see forceRewrite() method in this test)."
            + "\n3. Do a diff on the files - note attributes may have changed position."
            + "\n3.1 worth checking the \"undertow subsystem\" \"http(s) listener\" section first\n\n";

        Diff myDiff = DiffBuilder
                .compare(Input.fromFile(getCopiedStandaloneHaContent()))
                .withTest(Input.fromFile(getStandaloneHaContent()))
                .withNodeMatcher(new DefaultNodeMatcher(ElementSelectors.byName))
                .ignoreWhitespace()
                .ignoreComments()
                .build();

        myDiff.getDifferences().forEach((Difference t) ->
        {
            if (t.getResult() == DIFFERENT)
            {
                System.out.println("Difference: " + t.toString());
            }
        });
        assertFalse(msg, myDiff.hasDifferences());
    }

    /*
     * This method will force a rewrite of the standalone-ha file by using the cli interface to update a logging
     * attribute in the ha file.
     */
    @SuppressWarnings("unused")
    private void forceRewrite() throws Exception
    {
        /*
         * This is a bit rubbish, but it works. The only way I have managed to get the cli to talk to the server is via
         * the jboss-cli.bat file in the server bin directory. AND further more I have to feed the bat file the commands
         * from a text file.
         *
         * This method writes the text file, containing the commands and then executes the cli using these commands. The
         * commands simply wirte to one of the logging levels contained within the ha - so no big change.
         *
         */

        String jbossCliBatFilePath = null;

        File cliCommandFile = createCliCommandFile();

        // test if we have a local running test override for the ha file location..
        if (System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE) != null)
        {
            jbossCliBatFilePath = System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE) + "\\bin\\jboss-cli.bat";
        }
        else
        {
            jbossCliBatFilePath = SERVER_ROOT_DIR + "\\bin\\jboss-cli.bat";
        }

        String executionStr = jbossCliBatFilePath + " --file=" + cliCommandFile.getAbsolutePath();
        Process process = Runtime.getRuntime().exec(executionStr);

        // need this small sleep to allow the bat file time to execute
        System.out.println("Sleeping for 10secs to allow for rewrite");
        Thread.sleep(10000);

    }

    /*
     * This method creates the file containing the cli commands that are fed to the cli bat file and force the server to
     * rewrite the ha file.
     */
    private File createCliCommandFile() throws IOException
    {
        /*
         * Create the file from scratch each time.
         *
         */
        String cliCommandsPath = null;

        // test if we have a local running test override for the ha file location..
        if (System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE) != null)
        {
            cliCommandsPath = System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE) + "\\bin\\cliCommands.txt";
        }
        else
        {
            cliCommandsPath = SERVER_ROOT_DIR + "\\bin\\cliCommands.txt";
        }

        String cliCommands = "";
        cliCommands = "connect";
        cliCommands += "\ncd subsystem=logging";
        cliCommands += "\ncd logger=com.arjuna";
        cliCommands += "\n:write-attribute (name=level, value=WARN)";

        File cliCommandFile = new File(cliCommandsPath);
        cliCommandFile.deleteOnExit();
        IdbsIO.writeTextToFile(cliCommands, cliCommandFile);

        if (!cliCommandFile.exists())
        {
            System.out.println("cliCommandFile does NOT existExpected at: " + cliCommandFile.getAbsolutePath());
        }
        else
        {
            System.out.println("cliCommandFile DOES exist at: " + cliCommandFile.getAbsolutePath());
        }

        return cliCommandFile;
    }

    /*
     * In this method we are returning the content of the HA file. What is important here is that no matter how the xml
     * is constructed we get the same results, i.e. if attributes are different orders we should get the same result
     * between two files. To achieve this we do two things: a) strip out all whitespace. b) split xml into a list of
     * Strings (split on the "=" sign. We then oder the list. Thus any xml that is semantically the same will match even
     * if attributes are in different orders. This splitting and ordering is done in getHaContent().
     */
    private File getStandaloneHaContent() throws IOException
    {
        File standaloneHaFile;
        String standaloneHaPath;

        // test if we have a local running test override for the ha file location..
        if (System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE) != null)
        {
            standaloneHaPath = System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE)
                + "\\standalone\\configuration\\standalone-ewb-ha.xml";
            standaloneHaFile = new File(standaloneHaPath);
        }
        else
        {
            standaloneHaFile = new File(STANDALONE_HA_ACTUAL_FULLPATH);
        }

        return standaloneHaFile;
    }

    private File getCopiedStandaloneHaContent() throws IOException
    {
        File copyOfStandaloneHaFile;
        String standaloneHaPath;

        // test if we have a local running test override for the ha file location..
        if (System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE) != null)
        {
            standaloneHaPath = System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE)
                + "\\standalone\\configuration\\standalone-ewb-ha-copyForRewriteTests.xml";
            copyOfStandaloneHaFile = new File(standaloneHaPath);
        }
        else
        {
            copyOfStandaloneHaFile = new File(STANDALONE_HA_COPY_ACTUAL_FULLPATH);
        }

        return copyOfStandaloneHaFile;
    }


    private long getHaFileLastModifiedDate() throws IOException
    {
        long retVal = 0;

        File standaloneHaFile = null;
        String standaloneHaPath = null;

        // test if we have a local running test override for the ha file location..
        if (System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE) != null)
        {
            standaloneHaPath = System.getProperty(SERVER_ROOT_PATH_LOCAL_TEST_OVERRIDE)
                + "\\standalone\\configuration\\standalone-ewb-ha.xml";
            standaloneHaFile = new File(standaloneHaPath);
        }
        else
        {
            standaloneHaFile = new File(STANDALONE_HA_ACTUAL_FULLPATH);
        }

        if (standaloneHaFile.exists())
        {
            retVal = standaloneHaFile.lastModified();
        }
        else
        {
            String msg = "standalone-ewb-ha.xml file not found at: " + standaloneHaFile.toString()
                + "\nIf you are running locally you should override the location used for Integration Tests using -DlocalOverride-serverRootPath=[serverRoot]"
                + "\n(this is the server path upto the dir before \\standalone\\configuration\\... "
                + "\n\nIf this IS an integration test failure it is likely that the name of the Integration Test deployment directory has changed - "
                + "in which case the test needs updating, see test class comments";
            throw new IOException(msg);
        }

        return retVal;
    }
}
