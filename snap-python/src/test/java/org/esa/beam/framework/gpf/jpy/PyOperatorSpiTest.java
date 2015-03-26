package org.esa.beam.framework.gpf.jpy;


import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.OperatorSpiRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.net.URI;
import java.net.URL;

import static org.esa.beam.framework.gpf.jpy.PyOperatorSpi.EXT_PROPERTY_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * @author Norman Fomferra
 */
public class PyOperatorSpiTest {

    @BeforeClass
    public static void init() {
        URL resource = PyOperatorSpi.class.getResource("/beampy-examples");
        assertNotNull(resource);
        File file = new File(URI.create(resource.toString()));
        assertTrue(file.isDirectory());
        System.setProperty(EXT_PROPERTY_NAME, file.getPath());
        //System.out.printf("%s = %s%n", EXT_PROPERTY_NAME, System.getProperty(EXT_PROPERTY_NAME));
    }

    /*
     * Note that this test will not execute any Python code. It ensures, that the XML descriptor is
     * correctly looked up.
     */
    @Test
    public void testOperatorSpiIsLoaded() throws Exception {
        OperatorSpiRegistry registry = GPF.getDefaultInstance().getOperatorSpiRegistry();
        registry.loadOperatorSpis();
        OperatorSpi ndviOpSpi = registry.getOperatorSpi("py_ndvi_op");
        assertNotNull(ndviOpSpi);
        assertEquals("py_ndvi_op", ndviOpSpi.getOperatorAlias());
        assertSame(PyOperator.class, ndviOpSpi.getOperatorClass());
        assertNotNull(ndviOpSpi.getOperatorDescriptor());
        assertEquals("org.esa.beam.python.example.NdviOp", ndviOpSpi.getOperatorDescriptor().getName());
        assertSame(PyOperator.class, ndviOpSpi.getOperatorDescriptor().getOperatorClass());
    }
}