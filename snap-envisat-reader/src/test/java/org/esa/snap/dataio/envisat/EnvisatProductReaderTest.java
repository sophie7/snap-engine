/*
 * Copyright (C) 2011 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.snap.dataio.envisat;

import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EnvisatProductReaderTest {

    private EnvisatProductReaderPlugIn readerPlugIn;

    @Before
    public void setUp() {
        readerPlugIn = new EnvisatProductReaderPlugIn();
    }

    @Test
    public void testAatsrGeoLocation_UpperRightCorner() throws IOException, URISyntaxException {
        final EnvisatProductReader reader = (EnvisatProductReader) readerPlugIn.createReaderInstance();

        try {
            final Product product = reader.readProductNodes(
                    new File(getClass().getResource(
                            "ATS_TOA_1PRMAP20050504_080932_000000482037_00020_16607_0001.N1").toURI()), null);
            assertEquals(512, product.getSceneRasterWidth());
            assertEquals(320, product.getSceneRasterHeight());

            final TiePointGrid latGrid = product.getTiePointGrid("latitude");
            final TiePointGrid lonGrid = product.getTiePointGrid("longitude");
            assertNotNull(latGrid);
            assertNotNull(lonGrid);

            final ProductFile productFile = reader.getProductFile();
            assertTrue(productFile.storesPixelsInChronologicalOrder());

            final int colCount = 512;
            final int rowCount = 320;
            final float[] lats = new float[colCount * rowCount];
            final float[] lons = new float[colCount * rowCount];
            readFloats("image_latgrid_ATS_TOA_1PRMAP20050504_080932_000000482037_00020_16607_0001.txt", lats);
            readFloats("image_longrid_ATS_TOA_1PRMAP20050504_080932_000000482037_00020_16607_0001.txt", lons);

            final GeoPos geoPos = new GeoPos();

            product.getSceneGeoCoding().getGeoPos(new PixelPos(0.0F + 1.0F, 0.0F), geoPos);
            assertEquals(44.550718F, geoPos.getLat(), 1.0E-5F);
            assertEquals(32.878792F, geoPos.getLon(), 1.0E-5F);

            product.getSceneGeoCoding().getGeoPos(new PixelPos(5.0F + 1.0F, 0.0F), geoPos);
            assertEquals(44.541008F, geoPos.getLat(), 1.0E-5F);
            assertEquals(32.940249F, geoPos.getLon(), 1.0E-5F);

            for (int i = 0; i < rowCount; i++) {
                for (int j = 0, k = colCount - 1; j < colCount; j++, k--) {
                    product.getSceneGeoCoding().getGeoPos(new PixelPos(j + 1.0F, i + 0.0F), geoPos);
                    assertEquals(lats[i * colCount + k], geoPos.getLat(), 1.2E-5F);
                    assertEquals(lons[i * colCount + k], geoPos.getLon(), 1.2E-5F);
                }
            }
        } finally {
            reader.close();
        }
    }

    @Test
    public void testAatsrGeoLocation_Center() throws IOException, URISyntaxException {
        final EnvisatProductReader reader = (EnvisatProductReader) readerPlugIn.createReaderInstance();

        try {
            final Product product = reader.readProductNodes(
                    new File(getClass().getResource(
                            "ATS_TOA_1PRMAP20050504_080932_000000482037_00020_16607_0001.N1").toURI()), null);
            assertEquals(512, product.getSceneRasterWidth());
            assertEquals(320, product.getSceneRasterHeight());

            final TiePointGrid latGrid = product.getTiePointGrid("latitude");
            final TiePointGrid lonGrid = product.getTiePointGrid("longitude");
            assertNotNull(latGrid);
            assertNotNull(lonGrid);

            final ProductFile productFile = reader.getProductFile();
            assertTrue(productFile.storesPixelsInChronologicalOrder());

            final int colCount = 512;
            final int rowCount = 320;
            final float[] lats = new float[colCount * rowCount];
            final float[] lons = new float[colCount * rowCount];
            readFloats("image_latgrid_centre_ATS_TOA_1PRMAP20050504_080932_000000482037_00020_16607_0001.txt", lats);
            readFloats("image_longrid_centre_ATS_TOA_1PRMAP20050504_080932_000000482037_00020_16607_0001.txt", lons);

            final GeoPos geoPos = new GeoPos();

            for (int i = 0; i < rowCount; i++) {
                for (int j = 0, k = colCount - 1; j < colCount; j++, k--) {
                    product.getSceneGeoCoding().getGeoPos(new PixelPos(j + 0.5F, i + 0.5F), geoPos);
                    assertEquals(lats[i * colCount + k], geoPos.getLat(), 1.2E-5F);
                    assertEquals(lons[i * colCount + k], geoPos.getLon(), 1.2E-5F);
                }
            }
        } finally {
            reader.close();
        }
    }

    @Test
    public void testGetResolutionInKilometers() {
        assertEquals(0.3, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_FR_L1B_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.3, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_FRS_L1B_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.3, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_FSG_L1B_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.3, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_FRG_L1B_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.3, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_FR_L2_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.3, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_FRS_L2_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.3, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_FSG_L2_PRODUCT_TYPE_NAME), 1e-8);

        assertEquals(1.2, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_RR_L1B_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(1.2, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_RRG_L1B_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(1.2, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_RR_L2_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(1.2, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_RRC_L2_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(1.2, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.MERIS_RRV_L2_PRODUCT_TYPE_NAME), 1e-8);

        assertEquals(1.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.AATSR_L1B_TOA_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(1.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.AATSR_L2_NR_PRODUCT_TYPE_NAME), 1e-8);

        assertEquals(0.0125, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_APG_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.03, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_APP_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.225, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_AP_BP_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.15, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_APM_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.012, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_APS_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(1.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_GM1_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.225, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_IM_BP_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.0125, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_IMG_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.15, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_IMM_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.03, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_IMP_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.008, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_IMS_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.9, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_WS_BP_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.15, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_WSM_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.008, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_WSS_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.02, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_WVI_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(5.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L1B_WVS_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(5.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.ASAR_L2_WVW_PRODUCT_TYPE_NAME), 1e-8);

        assertEquals(0.225, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.SAR_IM__BP_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.0125, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.SAR_IMG_1P_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.075, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.SAR_IMM_1P_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.0125, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.SAR_IMP_1P_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(0.0125, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.SAR_IMS_1P_PRODUCT_TYPE_NAME), 1e-8);

        assertEquals(1.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.AT1_L1B_TOA_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(1.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.AT1_L2_NR_PRODUCT_TYPE_NAME), 1e-8);

        assertEquals(1.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.AT2_L1B_TOA_PRODUCT_TYPE_NAME), 1e-8);
        assertEquals(1.0, EnvisatProductReader.getResolutionInKilometers(EnvisatConstants.AT2_L2_NR_PRODUCT_TYPE_NAME), 1e-8);
    }

    private void readFloats(String resourceName, float[] floats) {
        final Scanner scanner = new Scanner(getClass().getResourceAsStream(resourceName), "US-ASCII");
        scanner.useLocale(Locale.ENGLISH);

        try {
            for (int i = 0; i < floats.length; i++) {
                floats[i] = scanner.nextFloat();
            }
        } finally {
            scanner.close();
        }
    }
}
