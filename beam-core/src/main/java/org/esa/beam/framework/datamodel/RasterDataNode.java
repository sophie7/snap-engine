/*
 * $Id: RasterDataNode.java,v 1.4 2007/03/19 15:52:28 marcop Exp $
 *
 * Copyright (C) 2002 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.framework.datamodel;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import com.bc.ceres.glevel.support.GenericMultiLevelSource;
import org.esa.beam.framework.dataop.barithm.BandArithmetic;
import org.esa.beam.jai.ImageManager;
import org.esa.beam.util.BitRaster;
import org.esa.beam.util.Debug;
import org.esa.beam.util.ObjectUtils;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.StringUtils;
import org.esa.beam.util.math.DoubleList;
import org.esa.beam.util.math.Histogram;
import org.esa.beam.util.math.IndexValidator;
import org.esa.beam.util.math.MathUtils;
import org.esa.beam.util.math.Quantizer;
import org.esa.beam.util.math.Range;
import org.esa.beam.util.math.Statistics;

import javax.media.jai.PlanarImage;
import javax.media.jai.ROI;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * The <code>RasterDataNode</code> class ist the abstract base class for all objects in the product package that contain
 * rasterized data. i.e. <code>Band</code> and <code>TiePointGrid</code>. It unifies the access to raster data in the
 * product model. A raster is considered as a rectangular raw data array with a fixed width and height. A raster data
 * node can scale its raw raster data samples in order to return geophysically meaningful pixel values.
 *
 * @author Norman Fomferra
 * @see #getRasterData()
 * @see #getRasterWidth()
 * @see #getRasterHeight()
 * @see #isScalingApplied()
 * @see #isLog10Scaled()
 * @see #getScalingFactor()
 * @see #getScalingOffset()
 */
public abstract class RasterDataNode extends DataNode implements Scaling {

    public final static String PROPERTY_NAME_IMAGE_INFO = "imageInfo";
    public final static String PROPERTY_NAME_BITMASK_OVERLAY_INFO = "bitmaskOverlayInfo";
    public final static String PROPERTY_NAME_LOG_10_SCALED = "log10Scaled";
    public final static String PROPERTY_NAME_ROI_DEFINITION = "roiDefinition";
    public final static String PROPERTY_NAME_SCALING_FACTOR = "scalingFactor";
    public final static String PROPERTY_NAME_SCALING_OFFSET = "scalingOffset";
    public final static String PROPERTY_NAME_SAMPLE_RANGE = "sampleRange";
    public final static String PROPERTY_NAME_SAMPLE_FREQUENCIES = "sampleFrequencies";
    public final static String PROPERTY_NAME_NO_DATA_VALUE = "noDataValue";
    public final static String PROPERTY_NAME_NO_DATA_VALUE_USED = "noDataValueUsed";
    public final static String PROPERTY_NAME_VALID_PIXEL_EXPRESSION = "validPixelExpression";
    public final static String PROPERTY_NAME_GEOCODING = Product.PROPERTY_NAME_GEOCODING;
    public final static String PROPERTY_NAME_STX = "stx";

    /**
     * Text returned by the <code>{@link #getPixelString}</code> method if no data is available at the given pixel
     * position.
     */
    public final static String NO_DATA_TEXT = "NaN"; /*I18N*/
    /**
     * Text returned by the <code>{@link #getPixelString}</code> method if no data is available at the given pixel
     * position.
     */
    public static final String INVALID_POS_TEXT = "Invalid pos."; /*I18N*/
    /**
     * Text returned by the <code>{@link #getPixelString}</code> method if pixel data was not loaded.
     */
    public static final String NOT_LOADED_TEXT = "Not loaded"; /*I18N*/
    /**
     * Text returned by the <code>{@link #getPixelString}</code> method if an I/O error occured while pixel data was
     * reloaded.
     */
    public static final String IO_ERROR_TEXT = "I/O error"; /*I18N*/


    /**
     * The raster's width.
     */
    private final int rasterWidth;

    /**
     * The raster's height.
     */
    private final int rasterHeight;

    private double scalingFactor;
    private double scalingOffset;
    private boolean log10Scaled;
    private boolean scalingApplied;

    private boolean noDataValueUsed;
    private ProductData noData;
    private double geophysicalNoDataValue; // invariant, depending on _noData
    private String validPixelExpression;

    private GeoCoding geoCoding;

    private Stx stx;

    private ImageInfo imageInfo;
    private BitmaskOverlayInfo bitmaskOverlayInfo;
    private ROIDefinition roiDefinition;

    /**
     * Number of bytes used for internal read buffer.
     */
    private static final int READ_BUFFER_MAX_SIZE = 8 * 1024 * 1024; // 8 MB
    private Pointing pointing;

    private MultiLevelImage sourceImage;
    private MultiLevelImage geophysicalImage;
    private MultiLevelImage validMaskImage;
    @Deprecated
    private ROI validMaskROI;

    /**
     * Constructs an object of type <code>RasterDataNode</code>.
     *
     * @param name     the name of the new object
     * @param dataType the data type used by the raster, must be one of the multiple <code>ProductData.TYPE_<i>X</i></code>
     *                 constants, with the exception of <code>ProductData.TYPE_UINT32</code>
     * @param width    the width of the raster in pixels
     * @param height   the height of the raster in pixels
     */
    protected RasterDataNode(String name, int dataType, int width, int height) {
        super(name, dataType, (long) width * height);
        if (dataType != ProductData.TYPE_INT8
            && dataType != ProductData.TYPE_INT16
            && dataType != ProductData.TYPE_INT32
            && dataType != ProductData.TYPE_UINT8
            && dataType != ProductData.TYPE_UINT16
            && dataType != ProductData.TYPE_UINT32
            && dataType != ProductData.TYPE_FLOAT32
            && dataType != ProductData.TYPE_FLOAT64) {
            throw new IllegalArgumentException("dataType is invalid");
        }
        rasterWidth = width;
        rasterHeight = height;
        scalingFactor = 1.0;
        scalingOffset = 0.0;
        log10Scaled = false;
        scalingApplied = false;

        noData = null;
        noDataValueUsed = false;
        geophysicalNoDataValue = 0.0;
        validPixelExpression = null;
    }

    /**
     * Returns the width in pixels of the scene represented by this product raster. By default, the method simply
     * returns <code>getRasterWidth()</code>.
     *
     * @return the scene width in pixels
     */
    public int getSceneRasterWidth() {
        return getRasterWidth();
    }

    /**
     * Returns the height in pixels of the scene represented by this product raster. By default, the method simply
     * returns <code>getRasterHeight()</code>.
     *
     * @return the scene height in pixels
     */
    public int getSceneRasterHeight() {
        return getRasterHeight();
    }

    /**
     * Returns the width of the raster used by this product raster.
     *
     * @return the width of the raster
     */
    public final int getRasterWidth() {
        return rasterWidth;
    }

    /**
     * Returns the height of the raster used by this product raster.
     *
     * @return the height of the raster
     */
    public final int getRasterHeight() {
        return rasterHeight;
    }

    /**
     * Returns the size of the raster for this band in bytes.
     *
     * @return the raster size in bytes
     */
    public long getRasterDataSizeInBytes() {
        return getRasterWidth() * getRasterHeight() * ProductData.getElemSize(getDataType());
    }

    /**
     * Returns the geo-coding of this {@link RasterDataNode}.
     *
     * @return the geo-coding
     */
    public GeoCoding getGeoCoding() {
        if (geoCoding == null) {
            final Product product = getProduct();
            if (product != null) {
                return product.getGeoCoding();
            }
        }
        return geoCoding;
    }

    /**
     * Sets the geo-coding for this {@link RasterDataNode}.
     * Also sets the geo-coding of the parent {@link Product} if it has no geo-coding yet.
     * <p>On property change, the method calls {@link #fireProductNodeChanged(String)} with the property
     * name {@link #PROPERTY_NAME_GEOCODING}.</p>
     *
     * @param geoCoding the new geo-coding
     *
     * @see Product#setGeoCoding(GeoCoding)
     */
    public void setGeoCoding(final GeoCoding geoCoding) {
        if (!ObjectUtils.equalObjects(geoCoding, this.geoCoding)) {
            this.geoCoding = geoCoding;

            // If our product has no geo-coding yet, it is set to the current one, if any
            if (this.geoCoding != null) {
                final Product product = getProduct();
                if (product != null && product.getGeoCoding() == null) {
                    product.setGeoCoding(this.geoCoding);
                }
            }
            fireProductNodeChanged(PROPERTY_NAME_GEOCODING);
        }
    }

    /**
     * Creates a {@link Pointing} applicable for this raster.
     *
     * @return the pointing object, or null if a pointing is not available
     */
    protected Pointing createPointing() {
        if (getGeoCoding() == null || getProduct() == null) {
            return null;
        }
        final PointingFactory factory = getProduct().getPointingFactory();
        if (factory == null) {
            return null;
        }
        return factory.createPointing(this);
    }

    /**
     * Gets a {@link Pointing} if one is available for this raster.
     * The methods calls {@link #createPointing()} if a pointing has not been set so far or if its {@link GeoCoding} changed
     * since the last creation of this raster's {@link Pointing} instance.
     *
     * @return the pointing object, or null if a pointing is not available
     */
    public Pointing getPointing() {
        if (pointing == null || pointing.getGeoCoding() == getGeoCoding()) {
            pointing = createPointing();
        }
        return pointing;
    }

    /**
     * Tests if this raster data node can be orthorectified.
     *
     * @return true, if so
     */
    public boolean canBeOrthorectified() {
        final Pointing pointing = getPointing();
        return pointing != null && pointing.canGetViewDir();
    }

    /**
     * Returns <code>true</code> if the pixel data contained in this band is "naturally" a floating point number type.
     *
     * @return true, if so
     */
    @Override
    public boolean isFloatingPointType() {
        return scalingApplied || super.isFloatingPointType();
    }

    /**
     * Returns the geophysical data type of this <code>RasterDataNode</code>. The value retuned is always one of the
     * <code>ProductData.TYPE_XXX</code> constants.
     *
     * @return the geophysical data type
     *
     * @see ProductData
     * @see #isScalingApplied()
     */
    public int getGeophysicalDataType() {
        if (isScalingApplied()) {
            if (ProductData.getElemSize(getDataType()) > 2) {
                return ProductData.TYPE_FLOAT64;
            } else {
                return ProductData.TYPE_FLOAT32;
            }
        }
        return getDataType();
    }

    /**
     * Gets the scaling factor which is applied to raw {@link <code>ProductData</code>}. The default value is
     * <code>1.0</code> (no factor).
     *
     * @return the scaling factor
     *
     * @see #isScalingApplied()
     */
    public final double getScalingFactor() {
        return scalingFactor;
    }

    /**
     * Sets the scaling factor which is applied to raw {@link <code>ProductData</code>}.
     *
     * @param scalingFactor the scaling factor
     *
     * @see #isScalingApplied()
     */
    public final void setScalingFactor(double scalingFactor) {
        if (this.scalingFactor != scalingFactor) {
            this.scalingFactor = scalingFactor;
            setScalingApplied();
            resetGeophysicalImage();
            fireProductNodeChanged(PROPERTY_NAME_SCALING_FACTOR);
            setGeophysicalNoDataValue();
            resetValidMask();
            setModified(true);
        }
    }

    /**
     * Gets the scaling offset which is applied to raw {@link <code>ProductData</code>}. The default value is
     * <code>0.0</code> (no offset).
     *
     * @return the scaling offset
     *
     * @see #isScalingApplied()
     */
    public final double getScalingOffset() {
        return scalingOffset;
    }

    /**
     * Sets the scaling offset which is applied to raw {@link <code>ProductData</code>}.
     *
     * @param scalingOffset the scaling offset
     *
     * @see #isScalingApplied()
     */
    public final void setScalingOffset(double scalingOffset) {
        if (this.scalingOffset != scalingOffset) {
            this.scalingOffset = scalingOffset;
            setScalingApplied();
            resetGeophysicalImage();
            fireProductNodeChanged(PROPERTY_NAME_SCALING_OFFSET);
            setGeophysicalNoDataValue();
            resetValidMask();
            setModified(true);
        }
    }

    /**
     * Gets whether or not the {@link <code>ProductData</code>} of this band has a negative binominal distribution and
     * thus the common logarithm (base 10) of the values is stored in the raw data. The default value is
     * <code>false</code>.
     *
     * @return whether or not the data is logging-10 scaled
     *
     * @see #isScalingApplied()
     */
    public final boolean isLog10Scaled() {
        return log10Scaled;
    }

    /**
     * Sets whether or not the {@link <code>ProductData</code>} of this band has a negative binominal distribution and
     * thus the common logarithm (base 10) of the values is stored in the raw data.
     *
     * @param log10Scaled whether or not the data is logging-10 scaled
     *
     * @see #isScalingApplied()
     */
    public final void setLog10Scaled(boolean log10Scaled) {
        if (this.log10Scaled != log10Scaled) {
            this.log10Scaled = log10Scaled;
            setScalingApplied();
            resetGeophysicalImage();
            setGeophysicalNoDataValue();
            resetValidMask();
            fireProductNodeChanged(PROPERTY_NAME_LOG_10_SCALED);
            setModified(true);
        }
    }

    /**
     * Tests whether scaling of raw raster data values is applied before they are returned as geophysically meaningful
     * pixel values. <p>The methods which return geophysical pixel values are all {@link #getPixels},  {@link
     * #setPixels}, {@link #readPixels} and {@link #writePixels} methods as well as the
     * <code>getPixel&lt;Type&gt;</code> and <code>setPixel&lt;Type&gt;</code> methods such as  {@link #getPixelFloat}
     * and {@link #setPixelFloat}.
     *
     * @return <code>true</code> if a conversion is applyied to raw data samples before the are retuned.
     *
     * @see #getScalingOffset
     * @see #getScalingFactor
     * @see #isLog10Scaled
     */
    public final boolean isScalingApplied() {
        return scalingApplied;
    }

    /**
     * Tests if the given name is the name of a property which is relevant for the computation of the valid mask.
     *
     * @param propertyName the  name to test
     *
     * @return {@code true}, if so.
     *
     * @since BEAM 4.2
     */
    public static boolean isValidMaskProperty(final String propertyName) {
        return PROPERTY_NAME_NO_DATA_VALUE.equals(propertyName)
               || PROPERTY_NAME_NO_DATA_VALUE_USED.equals(propertyName)
               || PROPERTY_NAME_VALID_PIXEL_EXPRESSION.equals(propertyName)
               || PROPERTY_NAME_DATA.equals(propertyName);
    }


    /**
     * Tests whether or not a no-data value has been specified. The no-data value is not-specified unless either
     * {@link #setNoDataValue(double)} or {@link #setGeophysicalNoDataValue(double)} is called.
     *
     * @return true, if so
     *
     * @see #isNoDataValueUsed()
     * @see #setNoDataValue(double)
     */
    public boolean isNoDataValueSet() {
        return noData != null;
    }

    /**
     * Clears the no-data value, so that {@link #isNoDataValueSet()} will return <code>false</code>.
     */
    public void clearNoDataValue() {
        noData = null;
        setGeophysicalNoDataValue();
    }

    /**
     * Tests whether or not the no-data value is used.
     * <p>The no-data value is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     *
     * @return true, if so
     *
     * @see #setNoDataValueUsed(boolean)
     * @see #isNoDataValueSet()
     */
    public boolean isNoDataValueUsed() {
        return noDataValueUsed;
    }

    /**
     * Sets whether or not the no-data value is used.
     * If the no-data value is enabled and the no-data value has not been set so far,
     * a default no-data value it is set with a value of to zero.
     * <p>The no-data value is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     * <p>On property change, the method calls {@link #fireProductNodeChanged(String)} with the property
     * name {@link #PROPERTY_NAME_NO_DATA_VALUE_USED}.
     *
     * @param noDataValueUsed true, if so
     *
     * @see #isNoDataValueUsed()
     */
    public void setNoDataValueUsed(boolean noDataValueUsed) {
        if (this.noDataValueUsed != noDataValueUsed) {
            this.noDataValueUsed = noDataValueUsed;
            resetValidMask();
            setModified(true);
            fireProductNodeChanged(PROPERTY_NAME_NO_DATA_VALUE_USED);
            fireProductNodeDataChanged();
        }
    }

    /**
     * Gets the no-data value as a primitive <code>double</code>.
     * <p>Note that the value returned is NOT necessarily the same as the value returned by
     * {@link #getGeophysicalNoDataValue()} because no scaling is applied.
     * <p>The no-data value is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     * <p>The method returns <code>0.0</code>, if no no-data value has been specified so far.
     *
     * @return the no-data value. It is returned as a <code>double</code> in order to cover all other numeric types.
     *
     * @see #setNoDataValue(double)
     * @see #isNoDataValueSet()
     */
    public double getNoDataValue() {
        return isNoDataValueSet() ? noData.getElemDouble() : 0.0;
    }

    /**
     * Sets the no-data value as a primitive <code>double</code>.
     * <p>Note that the given value is related to the "raw", un-scaled raster data.
     * In order to set the geophysical, scaled no-data value use the method
     * {@link #setGeophysicalNoDataValue(double)}.
     * <p>The no-data value is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     * <p>On property change, the method calls {@link #fireProductNodeChanged(String)} with the property
     * name {@link #PROPERTY_NAME_NO_DATA_VALUE}.
     *
     * @param noDataValue the no-data value. It is passed as a <code>double</code> in order to cover all other numeric types.
     *
     * @see #getNoDataValue()
     * @see #isNoDataValueSet()
     */
    public void setNoDataValue(final double noDataValue) {
        if (noData == null || getNoDataValue() != noDataValue) {
            if (noData == null) {
                noData = createCompatibleProductData(1);
            }
            noData.setElemDouble(noDataValue);
            setGeophysicalNoDataValue();
            if (isNoDataValueUsed()) {
                resetValidMask();
            }
            setModified(true);
            fireProductNodeChanged(PROPERTY_NAME_NO_DATA_VALUE);
            if (isNoDataValueUsed()) {
                fireProductNodeDataChanged();
            }
        }
    }

    /**
     * Gets the geophysical no-data value which is simply the scaled "raw" no-data value
     * returned by {@link #getNoDataValue()}.
     * <p>The no-data value is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     *
     * @return the geophysical no-data value
     *
     * @see #setGeophysicalNoDataValue(double)
     */
    public double getGeophysicalNoDataValue() {
        return geophysicalNoDataValue;
    }

    /**
     * Sets the geophysical no-data value which is simply the scaled "raw" no-data value
     * returned by {@link #getNoDataValue()}.
     * <p>The no-data value is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     * <p>On property change, the method calls {@link #fireProductNodeChanged(String)} with the property
     * name {@link #PROPERTY_NAME_NO_DATA_VALUE}.
     *
     * @param noDataValue the new geophysical no-data value
     *
     * @see #setGeophysicalNoDataValue(double)
     * @see #isNoDataValueSet()
     */
    public void setGeophysicalNoDataValue(double noDataValue) {
        setNoDataValue(scaleInverse(noDataValue));
    }

    /**
     * Gets the expression that is used to determine whether a pixel is valid or not.
     * For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     *
     * @return the valid mask expression.
     */
    public String getValidPixelExpression() {
        return validPixelExpression;
    }

    /**
     * Sets the expression that is used to determine whether a pixel is valid or not.
     * <p>The valid-pixel expression is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     * <p>On property change, the method calls {@link #fireProductNodeChanged(String)} with the property
     * name {@link #PROPERTY_NAME_VALID_PIXEL_EXPRESSION}.
     *
     * @param validPixelExpression the valid mask expression, can be null
     */
    public void setValidPixelExpression(final String validPixelExpression) {
        if (!ObjectUtils.equalObjects(this.validPixelExpression, validPixelExpression)) {
            this.validPixelExpression = validPixelExpression;
            resetValidMask();
            setModified(true);
            fireProductNodeChanged(PROPERTY_NAME_VALID_PIXEL_EXPRESSION);
            fireProductNodeDataChanged();
        }
    }

    /**
     * Tests whether or not this raster data node uses a data-mask in order to determine valid pixels. The method returns
     * true if either {@link #isValidPixelExpressionSet()} or {@link #isNoDataValueUsed()} returns true.
     * <p>The data-mask is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     *
     * @return true, if so
     *
     * @see #getValidMask()
     * @see #setValidMask(BitRaster)
     * @see #ensureValidMaskComputed(com.bc.ceres.core.ProgressMonitor)
     */
    public boolean isValidMaskUsed() {
        return isValidPixelExpressionSet() || isNoDataValueUsed();
    }

    /**
     * Gets the valid pixel mask which indicates if a pixel is valid or not. The method returns null if either
     * no data-mask is used ({@link #isValidMaskUsed()} returns false) or if the data-mask hasn't been created so far.
     * <p>The data-mask is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     *
     * @return the valid pixel mask, <code>null</code> if not set.
     *
     * @see #setValidMask(org.esa.beam.util.BitRaster)
     * @see #ensureValidMaskComputed(com.bc.ceres.core.ProgressMonitor)
     * @deprecated in BEAM 4.5, use {@link #getValidMaskImage()}
     */
    @Deprecated
    public BitRaster getValidMask() {
        return computeValidBitRaster();
    }

    /**
     * Sets the valid pixel mask which indicates if a pixel is valid or not.
     * <p>The data-mask is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     *
     * @param validMask the valid pixel mask, can be null.
     *
     * @see #getValidMask()
     * @see #ensureValidMaskComputed(com.bc.ceres.core.ProgressMonitor)
     * @deprecated in BEAM 4.5
     */
    @Deprecated
    protected void setValidMask(final BitRaster validMask) {
    }

    /**
     * Ensures that a data-mask, if any, is available, thus {@link #getValidMask()} returns a non-null value.
     * The method shall be called once before the {@link #isPixelValid(int,int,javax.media.jai.ROI)} method is called.
     * <p>The data-mask is used to determine valid pixels. For more information
     * on valid pixels, please refer to the documentation of the {@link #isPixelValid(int,int,javax.media.jai.ROI)}
     * method.
     *
     * @param pm The progress monitor.
     *
     * @throws IOException if an I/O error occurs
     * @deprecated in BEAM 4.5, no replacement
     */
    @Deprecated
    public void ensureValidMaskComputed(ProgressMonitor pm) throws IOException {
    }

    protected void resetValidMask() {
        validMaskROI = null;
        validMaskImage = null;
        stx = null;
    }

    /**
     * Gets the expression used for the computation of the mask which identifies valid pixel values.
     * It recognizes the value of the {@link #getNoDataValue() noDataValue} and the
     * {@link #getValidPixelExpression() validPixelExpression} properties, if any.
     * The method returns {@code null},  if none of these properties are set.
     *
     * @return The expression used for the computation of the mask which identifies valid pixel values,
     *         or {@code null}.
     *
     * @see #getValidPixelExpression()
     * @see #getNoDataValue()
     * @since BEAM 4.2
     */
    public String getValidMaskExpression() {
        String dataMaskExpression = null;
        if (isValidPixelExpressionSet()) {
            dataMaskExpression = getValidPixelExpression();
            if (isNoDataValueUsed()) {
                final String dataMaskExpression2 = createValidMaskExpressionForNoDataValue();
                if (!dataMaskExpression2.equals(dataMaskExpression)) {
                    dataMaskExpression = "(" + dataMaskExpression + ") && " + dataMaskExpression2;
                }
            }
        } else if (isNoDataValueUsed()) {
            dataMaskExpression = createValidMaskExpressionForNoDataValue();
        }
        return dataMaskExpression;
    }

    private String createValidMaskExpressionForNoDataValue() {
        final String ref = BandArithmetic.createExternalName(getName());
        final double noDataValue = getGeophysicalNoDataValue();
        if (Double.isNaN(noDataValue)) {
            return "!nan(" + ref + ")";
        } else if (Double.isInfinite(noDataValue)) {
            return "!inf(" + ref + ")";
        } else {
            return "fneq(" + ref + "," + noDataValue + ")";
        }
    }

    @Deprecated
    protected synchronized void computeValidMask(ProgressMonitor pm) throws IOException {
    }

    private BitRaster computeValidBitRaster() {
        BitRaster validBitMask = new BitRaster(getSceneRasterWidth(), getSceneRasterHeight());
        if (isValidMaskUsed()) {
            ROI roi = getValidMaskROI();
            for (int y = 0; y < getSceneRasterHeight(); y++) {
                for (int x = 0; x < getSceneRasterWidth(); x++) {
                    validBitMask.set(x, y, roi.contains(x, y));
                }
            }
        } else {
            for (int i = 0; i < getSceneRasterWidth() * getSceneRasterHeight(); i++) {
                validBitMask.set(i);
            }
        }
        return validBitMask;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateExpression(final String oldExternalName, final String newExternalName) {
        if (validPixelExpression == null) {
            return;
        }
        final String expression = StringUtils.replaceWord(validPixelExpression, oldExternalName, newExternalName);
        if (!validPixelExpression.equals(expression)) {
            validPixelExpression = expression;
            setModified(true);
        }

        if (roiDefinition != null) {
            final String bitmaskExpr = roiDefinition.getBitmaskExpr();
            if (!StringUtils.isNullOrEmpty(bitmaskExpr) && bitmaskExpr.contains(oldExternalName)) {
                final String newBitmaskExpression = StringUtils.replaceWord(bitmaskExpr, oldExternalName,
                                                                            newExternalName);
                final ROIDefinition newRoiDef = roiDefinition.createCopy();
                newRoiDef.setBitmaskExpr(newBitmaskExpression);
                // a new roi definition must be set to inform product node listeners because a roi sourceImage
                // is only automatically updated if a product node listener is informed of changes.
                // A roi definition is not a product node so that a product node listener can not be
                // informed if an expression is changed.
                setROIDefinition(newRoiDef);
            }
        }

        super.updateExpression(oldExternalName, newExternalName);
    }

    /**
     * Gets a raster data holding this dataset's pixel data for an entire product scene. If the data has'nt been loaded
     * so far the method returns <code>null</code>.
     * <p/>
     * <p>In oposite to the <code>getRasterData</code> method, this method returns raster data that has at least
     * <code>getBandOutputRasterWidth()*getBandOutputRasterHeight()</code> elements of the given data type to store the
     * scene's pixels.
     *
     * @return raster data covering the pixels for a complete scene
     *
     * @see #getRasterData
     * @see #getRasterWidth
     * @see #getRasterHeight
     * @see #getSceneRasterWidth
     * @see #getSceneRasterHeight
     */
    public abstract ProductData getSceneRasterData();


    /**
     * Returns true if the raster data of this <code>RasterDataNode</code> is loaded or elsewhere available, otherwise
     * false.
     *
     * @return true, if so.
     */
    public boolean hasRasterData() {
        return getRasterData() != null;
    }


    /**
     * Gets the raster data for this dataset. If the data has'nt been loaded so far the method returns
     * <code>null</code>.
     *
     * @return the raster data for this band, or <code>null</code> if data has not been loaded
     *
     * @see #setRasterData
     */
    public ProductData getRasterData() {
        return getData();
    }

    /**
     * Sets the raster data of this dataset.
     * <p/>
     * <p> Note that this method does not copy data at all. If the supplied raster data is compatible with this product
     * raster, then simply its reference is stored. Modifications in the supplied raster data will also affect this
     * dataset's data!
     *
     * @param rasterData the raster data for this dataset
     *
     * @see #getRasterData()
     */
    public void setRasterData(ProductData rasterData) {
        setData(rasterData);
    }

    /**
     * @throws java.io.IOException if an I/O error occurs
     * @see #loadRasterData(com.bc.ceres.core.ProgressMonitor)
     */
    public void loadRasterData() throws IOException {
        loadRasterData(ProgressMonitor.NULL);
    }

    /**
     * Loads the raster data for this <code>RasterDataNode</code>. After this method has been called successfully,
     * <code>hasRasterData()</code> should always return <code>true</code> and <code>getRasterData()</code> should
     * always return a valid <code>ProductData</code> instance with at least <code>getRasterWidth()*getRasterHeight()</code>
     * elements (samples).
     * <p/>
     * <p>The default implementation of this method does nothing.
     *
     * @param pm a monitor to inform the user about progress
     *
     * @throws IOException if an I/O error occurs
     * @see #unloadRasterData()
     */
    public void loadRasterData(ProgressMonitor pm) throws IOException {
    }

    /**
     * Un-loads the raster data for this <code>RasterDataNode</code>.
     * <p/>
     * <p>It is up to the implementation whether after this method has been called successfully, the
     * <code>hasRasterData()</code> method returns <code>false</code> or <code>true</code>.
     * <p/>
     * <p>The default implementation of this method does nothing.
     *
     * @see #loadRasterData()
     */
    public void unloadRasterData() {
    }

    /**
     * Releases all of the resources used by this object instance and all of its owned children. Its primary use is to
     * allow the garbage collector to perform a vanilla job.
     * <p/>
     * <p>This method should be called only if it is for sure that this object instance will never be used again. The
     * results of referencing an instance of this class after a call to <code>dispose()</code> are undefined.
     * <p/>
     * <p>Overrides of this method should always call <code>super.dispose();</code> after disposing this instance.
     */
    @Override
    public void dispose() {
        if (imageInfo != null) {
            imageInfo.dispose();
            imageInfo = null;
        }
        if (bitmaskOverlayInfo != null) {
            bitmaskOverlayInfo.dispose();
            bitmaskOverlayInfo = null;
        }
        if (roiDefinition != null) {
            roiDefinition.dispose();
            roiDefinition = null;
        }
        if (sourceImage != null) {
//            if (sourceImage instanceof PlanarImage) {
//                PlanarImage planarImage = (PlanarImage) sourceImage;
//                planarImage.dispose();
//            }
            sourceImage = null;
        }
        if (validMaskROI != null) {
            validMaskROI = null;
        }
        if (validMaskImage != null) {
//            if (validMaskImage instanceof PlanarImage) {
//                PlanarImage planarImage = (PlanarImage) validMaskImage;
//                planarImage.dispose();
//            }
            validMaskImage = null;
        }
        if (geophysicalImage != null) {
//            if (geophysicalImage instanceof PlanarImage) {
//                PlanarImage planarImage = (PlanarImage) geophysicalImage;
//                planarImage.dispose();
//            }
            geophysicalImage = null;
        }
        super.dispose();
    }

    /**
     * Checks whether or not the pixel located at (x,y) is valid.
     * A pixel is assumed to be valid either if  {@link #getValidMask()} returns null or
     * or if the bit corresponding to (x,y) is set within the returned mask.
     * <p>In order to set the valid pixel mask, the method {@link #ensureValidMaskComputed(com.bc.ceres.core.ProgressMonitor)} shall
     * be called before this method returns reasonable results.
     * {@link #ensureValidMaskComputed(com.bc.ceres.core.ProgressMonitor)} will ensure that a data-mask will be computed
     * either {@link #isNoDataValueUsed()} returns true or if {@link #getValidPixelExpression()} returns a non-empty
     * expression.
     *
     * @param x the X co-ordinate of the pixel location
     * @param y the Y co-ordinate of the pixel location
     *
     * @return <code>true</code> if the pixel is valid
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     * @see #isPixelValid(int,int,javax.media.jai.ROI)
     * @see #setNoDataValueUsed(boolean)
     * @see #setNoDataValue(double)
     * @see #setValidPixelExpression(String)
     */
    public boolean isPixelValid(int x, int y) {
        if (isValidMaskUsed()) {
            return getValidMaskROI().contains(x, y);
        }
        return true;
    }

    /**
     * Checks whether or not the pixel located at (x,y) is valid.
     * A pixel is assumed to be valid either if  {@link #getValidMask()} returns null or
     * or if the bit corresponding to (x,y) is set within the returned mask.
     * <p>In order to set the valid pixel mask, the method {@link #ensureValidMaskComputed(com.bc.ceres.core.ProgressMonitor)} shall
     * be called before this method returns reasonable results.
     * {@link #ensureValidMaskComputed(com.bc.ceres.core.ProgressMonitor)} will ensure that a data-mask will be computed
     * either {@link #isNoDataValueUsed()} returns true or if {@link #getValidPixelExpression()} returns a non-empty
     * expression.
     *
     * @param pixelIndex the linear pixel index in the range 0 to width * height - 1
     *
     * @return <code>true</code> if the pixel is valid
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     * @see #isPixelValid(int,int,javax.media.jai.ROI)
     * @see #setNoDataValueUsed(boolean)
     * @see #setNoDataValue(double)
     * @see #setValidPixelExpression(String)
     * @since 4.1
     */
    public boolean isPixelValid(int pixelIndex) {
        if (!isValidMaskUsed()) {
            return true;
        }
        final int y = pixelIndex / getSceneRasterWidth();
        final int x = pixelIndex - (y * getSceneRasterWidth());
        return isPixelValid(x, y);
    }

    /**
     * Checks whether or not the pixel located at (x,y) is valid.
     * The method first test whether a pixel is valid by using the {@link #isPixelValid(int,int)} method,
     * and secondly, if the pixel is within the ROI (if any).
     *
     * @param x   the X co-ordinate of the pixel location
     * @param y   the Y co-ordinate of the pixel location
     * @param roi the ROI, if null the method returns {@link #isPixelValid(int,int)}
     *
     * @return <code>true</code> if the pixel is valid
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     * @see #isPixelValid(int,int)
     * @see #setNoDataValueUsed(boolean)
     * @see #setNoDataValue(double)
     * @see #setValidPixelExpression(String)
     */
    public boolean isPixelValid(int x, int y, ROI roi) {
        return isPixelValid(x, y) && (roi == null || roi.contains(x, y));
    }

    /**
     * Returns the pixel located at (x,y) as an integer value.
     *
     * @param x the X co-ordinate of the pixel location
     * @param y the Y co-ordinate of the pixel location
     *
     * @return the pixel value at (x,y)
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     */
    public abstract int getPixelInt(int x, int y);

    /**
     * Returns the pixel located at (x,y) as a float value.
     *
     * @param x the X co-ordinate of the pixel location
     * @param y the Y co-ordinate of the pixel location
     *
     * @return the pixel value at (x,y)
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     */
    public abstract float getPixelFloat(int x, int y);

    /**
     * Returns the pixel located at (x,y) as a double value.
     *
     * @param x the X co-ordinate of the pixel location
     * @param y the Y co-ordinate of the pixel location
     *
     * @return the pixel value at (x,y)
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     */
    public abstract double getPixelDouble(int x, int y);

    /**
     * Sets the pixel located at (x,y) to the given integer value.
     *
     * @param x          the X co-ordinate of the pixel location
     * @param y          the Y co-ordinate of the pixel location
     * @param pixelValue the new pixel value at (x,y)
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     */
    public abstract void setPixelInt(int x, int y, int pixelValue);

    /**
     * Sets the pixel located at (x,y) to the given float value.
     *
     * @param x          the X co-ordinate of the pixel location
     * @param y          the Y co-ordinate of the pixel location
     * @param pixelValue the new pixel value at (x,y)
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     */
    public abstract void setPixelFloat(int x, int y, float pixelValue);

    /**
     * Sets the pixel located at (x,y) to the given double value.
     *
     * @param x          the X co-ordinate of the pixel location
     * @param y          the Y co-ordinate of the pixel location
     * @param pixelValue the new pixel value at (x,y)
     *
     * @throws ArrayIndexOutOfBoundsException if the co-ordinates are not in bounds
     */
    public abstract void setPixelDouble(int x, int y, double pixelValue);


    /**
     * @see #getPixels(int,int,int,int,int[],ProgressMonitor)
     */
    public int[] getPixels(int x, int y, int w, int h, int[] pixels) {
        return getPixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Retrieves the range of pixels specified by the coordinates as integer array. Throws exception when the data is
     * not read from disk yet. If the given array is <code>null</code> a new one was created and returned.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be read
     * @param h      height of the pixel array to be read.
     * @param pixels integer array to be filled with data
     * @param pm     a monitor to inform the user about progress
     */
    public abstract int[] getPixels(int x, int y, int w, int h, int[] pixels, ProgressMonitor pm);

    /**
     * @see #getPixels(int,int,int,int,float[],ProgressMonitor)
     */
    public float[] getPixels(int x, int y, int w, int h, float[] pixels) {
        return getPixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Retrieves the range of pixels specified by the coordinates as float array. Throws exception when the data is not
     * read from disk yet. If the given array is <code>null</code> a new one is created and returned.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be read
     * @param h      height of the pixel array to be read.
     * @param pixels float array to be filled with data
     * @param pm     a monitor to inform the user about progress
     */
    public abstract float[] getPixels(int x, int y, int w, int h, float[] pixels, ProgressMonitor pm);

    /**
     * @see #getPixels(int,int,int,int,double[],ProgressMonitor)
     */
    public double[] getPixels(int x, int y, int w, int h, double[] pixels) {
        return getPixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Retrieves the range of pixels specified by the coordinates as double array. Throws exception when the data is not
     * read from disk yet. If the given array is <code>null</code> a new one is created and returned.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be read
     * @param h      height of the pixel array to be read.
     * @param pixels double array to be filled with data
     * @param pm     a monitor to inform the user about progress
     */
    public abstract double[] getPixels(int x, int y, int w, int h, double[] pixels, ProgressMonitor pm);


    /**
     * Sets a range of pixels specified by the coordinates as integer array. Copies the data to the memory buffer of
     * data at the specified location. Throws exception when the target buffer is not in memory.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be written
     * @param h      height of the pixel array to be written.
     * @param pixels integer array to be written
     *
     * @throws NullPointerException if this band has no raster data
     */
    public abstract void setPixels(int x, int y, int w, int h, int[] pixels);

    /**
     * Sets a range of pixels specified by the coordinates as float array. Copies the data to the memory buffer of data
     * at the specified location. Throws exception when the target buffer is not in memory.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be written
     * @param h      height of the pixel array to be written.
     * @param pixels float array to be written
     *
     * @throws NullPointerException if this band has no raster data
     */
    public abstract void setPixels(int x, int y, int w, int h, float[] pixels);

    /**
     * Sets a range of pixels specified by the coordinates as double array. Copies the data to the memory buffer of data
     * at the specified location. Throws exception when the target buffer is not in memory.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be written
     * @param h      height of the pixel array to be written.
     * @param pixels double array to be written
     *
     * @throws NullPointerException if this band has no raster data
     */
    public abstract void setPixels(int x, int y, int w, int h, double[] pixels);

    /**
     * @see #readPixels(int,int,int,int,int[],ProgressMonitor)
     */
    public int[] readPixels(int x, int y, int w, int h, int[] pixels) throws IOException {
        return readPixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Retrieves the band data at the given offset (x, y), width and height as int data. If the data is already in
     * memory, it merely copies the data to the buffer provided. If not, it calls the attached product reader to
     * retrieve the data from the disk file. If the given buffer is <code>null</code> a new one was created and
     * returned.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be read
     * @param h      height of the pixel array to be read
     * @param pixels array to be filled with data
     * @param pm     a progress monitor
     *
     * @return the pixels read
     */
    public abstract int[] readPixels(int x, int y, int w, int h, int[] pixels, ProgressMonitor pm) throws IOException;

    /**
     * @see #readPixels(int,int,int,int,float[],ProgressMonitor)
     */
    public float[] readPixels(int x, int y, int w, int h, float[] pixels) throws IOException {
        return readPixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Retrieves the band data at the given offset (x, y), width and height as float data. If the data is already in
     * memory, it merely copies the data to the buffer provided. If not, it calls the attached product reader to
     * retrieve the data from the disk file. If the given buffer is <code>null</code> a new one was created and
     * returned.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be read
     * @param h      height of the pixel array to be read
     * @param pixels array to be filled with data
     * @param pm     a progress monitor
     *
     * @return the pixels read
     */
    public abstract float[] readPixels(int x, int y, int w, int h, float[] pixels, ProgressMonitor pm) throws
                                                                                                       IOException;

    /**
     * @see #readPixels(int,int,int,int,double[],ProgressMonitor)
     */
    public double[] readPixels(int x, int y, int w, int h, double[] pixels) throws IOException {
        return readPixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Retrieves the band data at the given offset (x, y), width and height as double data. If the data is already in
     * memory, it merely copies the data to the buffer provided. If not, it calls the attached product reader to
     * retrieve the data from the disk file. If the given buffer is <code>null</code> a new one was created and
     * returned.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be read
     * @param h      height of the pixel array to be read
     * @param pixels array to be filled with data
     * @param pm     a progress monitor
     *
     * @return the pixels read
     */
    public abstract double[] readPixels(int x, int y, int w, int h, double[] pixels, ProgressMonitor pm) throws
                                                                                                         IOException;

    /**
     * @see #writePixels(int,int,int,int,int[],ProgressMonitor)
     */
    public void writePixels(int x, int y, int w, int h, int[] pixels) throws IOException {
        writePixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Writes the range of given pixels specified to the specified coordinates as integers.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be written
     * @param h      height of the pixel array to be written
     * @param pixels array of pixels to write
     * @param pm     a progress monitor
     */
    public abstract void writePixels(int x, int y, int w, int h, int[] pixels, ProgressMonitor pm) throws IOException;

    /**
     * @see #writePixels(int,int,int,int,float[],ProgressMonitor)
     */
    public synchronized void writePixels(int x, int y, int w, int h, float[] pixels) throws IOException {
        writePixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Writes the range of given pixels specified to the specified coordinates as floats.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be written
     * @param h      height of the pixel array to be written
     * @param pixels array of pixels to write
     * @param pm     a progress monitor
     */
    public abstract void writePixels(int x, int y, int w, int h, float[] pixels, ProgressMonitor pm) throws IOException;

    /**
     * @see #writePixels(int,int,int,int,double[],ProgressMonitor)
     */
    public void writePixels(int x, int y, int w, int h, double[] pixels) throws IOException {
        writePixels(x, y, w, h, pixels, ProgressMonitor.NULL);
    }

    /**
     * Writes the range of given pixels specified to the specified coordinates as doubles.
     *
     * @param x      x offset into the band
     * @param y      y offset into the band
     * @param w      width of the pixel array to be written
     * @param h      height of the pixel array to be written
     * @param pixels array of pixels to write
     * @param pm     a progress monitor
     */
    public abstract void writePixels(int x, int y, int w, int h, double[] pixels, ProgressMonitor pm) throws
                                                                                                      IOException;

    public boolean[] readValidMask(int x, int y, int w, int h, boolean[] validMask) throws IOException {
        if (validMask == null) {
            validMask = new boolean[w * h];
        }
        if (isValidMaskUsed()) {
            int index = 0;
            ROI roi = getValidMaskROI();
            for (int yi = y; yi < y + h; yi++) {
                for (int xi = x; xi < x + w; xi++) {
                    validMask[index] = roi.contains(xi, yi);
                    index++;
                }
            }
        } else {
            Arrays.fill(validMask, true);
        }
        return validMask;
    }

    /**
     * @throws java.io.IOException if an I/O error occurs
     * @see #readRasterDataFully(ProgressMonitor)
     */
    public void readRasterDataFully() throws IOException {
        readRasterDataFully(ProgressMonitor.NULL);
    }

    /**
     * Reads the complete underlying raster data.
     * <p/>
     * <p>After this method has been called successfully, <code>hasRasterData()</code> should always return
     * <code>true</code> and <code>getRasterData()</code> should always return a valid <code>ProductData</code> instance
     * with at least <code>getRasterWidth()*getRasterHeight()</code> elements (samples).
     * <p/>
     * <p>In opposite to the <code>loadRasterData</code> method, the <code>readRasterDataFully</code> method always
     * reloads the data of this product raster, independently of whether its has already been loaded or not.
     *
     * @param pm a monitor to inform the user about progress
     *
     * @throws java.io.IOException if an I/O error occurs
     * @see #loadRasterData
     * @see #readRasterData(int,int,int,int,ProductData,com.bc.ceres.core.ProgressMonitor)
     */
    public abstract void readRasterDataFully(ProgressMonitor pm) throws IOException;

    /**
     * Reads raster data from the node's associated data source into the given data
     * buffer.
     *
     * @param offsetX    the X-offset in the raster co-ordinates where reading starts
     * @param offsetY    the Y-offset in the raster co-ordinates where reading starts
     * @param width      the width of the raster data buffer
     * @param height     the height of the raster data buffer
     * @param rasterData a raster data buffer receiving the pixels to be read
     *
     * @throws java.io.IOException      if an I/O error occurs
     * @throws IllegalArgumentException if the raster is null
     * @throws IllegalStateException    if this product raster was not added to a product so far, or if the product to
     *                                  which this product raster belongs to, has no associated product reader
     * @see org.esa.beam.framework.dataio.ProductReader#readBandRasterData(Band,int,int,int,int,ProductData,com.bc.ceres.core.ProgressMonitor)
     */
    public void readRasterData(int offsetX, int offsetY,
                               int width, int height,
                               ProductData rasterData) throws IOException {
        readRasterData(offsetX, offsetY, width, height, rasterData, ProgressMonitor.NULL);
    }

    /**
     * The method behaves exactly as {@link #readRasterData(int, int, int, int, ProductData)},
     * but clients can additionally pass a {@link ProgressMonitor}.
     *
     * @param offsetX    the X-offset in the raster co-ordinates where reading starts
     * @param offsetY    the Y-offset in the raster co-ordinates where reading starts
     * @param width      the width of the raster data buffer
     * @param height     the height of the raster data buffer
     * @param rasterData a raster data buffer receiving the pixels to be read
     * @param pm         a monitor to inform the user about progress
     *
     * @throws java.io.IOException      if an I/O error occurs
     * @throws IllegalArgumentException if the raster is null
     * @throws IllegalStateException    if this product raster was not added to a product so far, or if the product to
     *                                  which this product raster belongs to, has no associated product reader
     */
    public abstract void readRasterData(int offsetX, int offsetY,
                                        int width, int height,
                                        ProductData rasterData,
                                        ProgressMonitor pm) throws IOException;

    /**
     * Reads raster values from this dataset into the user-supplied data buffer.
     * Raster coordinates refer to the product's scene raster.
     * <p>If necessary this method will read spatially interpolated pixel data.</p>
     *
     * @param rectangle  the rectangle in scene raster co-ordinates of the data buffer
     * @param rasterData a raster data buffer receiving the pixels to be read
     * @param pm         a monitor to inform the user about progress
     *
     * @throws java.io.IOException      if an I/O error occurs
     * @throws IllegalArgumentException if the raster is null
     * @throws IllegalStateException    if this product raster was not added to a product so far, or if the product to
     *                                  which this product raster belongs to, has no associated product reader
     * @deprecated since BEAM 4.6, use {@link #readRasterData(int, int, int, int, ProductData)} instead
     */
    @Deprecated
    public void readRaster(Rectangle rectangle, ProductData rasterData, ProgressMonitor pm) throws IOException {
        readRasterData(rectangle.x, rectangle.y, rectangle.width, rectangle.height, rasterData, pm);
    }

    public void writeRasterDataFully() throws IOException {
        writeRasterDataFully(ProgressMonitor.NULL);
    }

    /**
     * Writes the complete underlying raster data.
     *
     * @param pm a monitor to inform the user about progress
     *
     * @throws java.io.IOException if an I/O error occurs
     */
    public abstract void writeRasterDataFully(ProgressMonitor pm) throws IOException;

    public void writeRasterData(int offsetX, int offsetY, int width, int height, ProductData rasterData)
            throws IOException {
        writeRasterData(offsetX, offsetY, width, height, rasterData, ProgressMonitor.NULL);
    }

    /**
     * Writes data from this product raster into the specified region of the user-supplied raster.
     * <p/>
     * <p> It is important to know that this method does not change this product raster's internal state nor does it
     * write into this product raster's internal raster.
     *
     * @param rasterData a raster data buffer receiving the pixels to be read
     * @param offsetX    the X-offset in raster co-ordinates where reading starts
     * @param offsetY    the Y-offset in raster co-ordinates where reading starts
     * @param width      the width of the raster data buffer
     * @param height     the height of the raster data buffer
     * @param pm         a monitor to inform the user about progress
     *
     * @throws java.io.IOException      if an I/O error occurs
     * @throws IllegalArgumentException if the raster is null
     * @throws IllegalStateException    if this product raster was not added to a product so far, or if the product to
     *                                  which this product raster belongs to, has no associated product reader
     * @see org.esa.beam.framework.dataio.ProductReader#readBandRasterData(Band,int,int,int,int,ProductData,com.bc.ceres.core.ProgressMonitor)
     */
    public abstract void writeRasterData(int offsetX, int offsetY,
                                         int width, int height,
                                         ProductData rasterData,
                                         ProgressMonitor pm) throws IOException;

    /**
     * Creates raster data that is compatible to this dataset's data type. The data buffer returned contains exactly
     * <code>getRasterWidth()*getRasterHeight()</code> elements of a compatible data type.
     *
     * @return raster data compatible with this product raster
     *
     * @see #createCompatibleSceneRasterData
     */
    public ProductData createCompatibleRasterData() {
        return createCompatibleRasterData(getRasterWidth(), getRasterHeight());
    }

    /**
     * Creates raster data that is compatible to this dataset's data type. The data buffer returned contains exactly
     * <code>getBandOutputRasterWidth()*getBandOutputRasterHeight()</code> elements of a compatible data type.
     *
     * @return raster data compatible with this product raster
     *
     * @see #createCompatibleRasterData
     */
    public ProductData createCompatibleSceneRasterData() {
        return createCompatibleRasterData(getSceneRasterWidth(), getSceneRasterHeight());
    }

    /**
     * Creates raster data that is compatible to this dataset's data type. The data buffer returned contains exactly
     * <code>width*height</code> elements of a compatible data type.
     *
     * @param width  the width of the raster data to be created
     * @param height the height of the raster data to be created
     *
     * @return raster data compatible with this product raster
     *
     * @see #createCompatibleRasterData
     * @see #createCompatibleSceneRasterData
     */
    public ProductData createCompatibleRasterData(int width, int height) {
        return createCompatibleProductData(width * height);
    }

    /**
     * Tests whether the given parameters specify a compatible raster or not.
     *
     * @param rasterData the raster data
     * @param w          the raster width
     * @param h          the raster height
     *
     * @return {@code true} if so
     */
    public boolean isCompatibleRasterData(ProductData rasterData, int w, int h) {
        return rasterData != null
               && rasterData.getType() == getDataType()
               && rasterData.getNumElems() == w * h;
    }

    /**
     * Throws an <code>IllegalArgumentException</code> if the given parameters dont specify a compatible raster.
     *
     * @param rasterData the raster data
     * @param w          the raster width
     * @param h          the raster height
     */
    public void checkCompatibleRasterData(ProductData rasterData, int w, int h) {
        if (!isCompatibleRasterData(rasterData, w, h)) {
            throw new IllegalArgumentException("invalid raster data buffer for '" + getName() + "'");
        }
    }

    /**
     * Determines whether this raster data node contains integer samples.
     *
     * @return true if this raster data node contains integer samples.
     */
    public boolean hasIntPixels() {
        return ProductData.isIntType(getDataType());
    }

    /**
     * Creates a transect profile for the given shape (-outline).
     *
     * @param shape the shape
     *
     * @return the profile data
     *
     * @throws IOException if an I/O error occurs
     */
    public TransectProfileData createTransectProfileData(Shape shape) throws IOException {
        return TransectProfileData.create(this, shape);
    }

    /**
     * Accepts the given visitor. This method implements the well known 'Visitor' design pattern of the gang-of-four.
     * The visitor pattern allows to define new operations on the product data model without the need to add more code
     * to it. The new operation is implemented by the visitor.
     *
     * @param visitor the visitor, must not be <code>null</code>
     */
    @Override
    public abstract void acceptVisitor(ProductVisitor visitor);

    /**
     * Gets the image information for image display.
     *
     * @return the image info or null
     */
    public ImageInfo getImageInfo() {
        return imageInfo;
    }

    /**
     * Sets the image information for image display.
     *
     * @param imageInfo the image info, can be null
     */
    public void setImageInfo(ImageInfo imageInfo) {
        setImageInfo(imageInfo, true);
    }

    protected void setImageInfo(ImageInfo imageInfo, boolean change) {
        if (this.imageInfo != imageInfo) {
            this.imageInfo = imageInfo;
            if (change) {
                fireProductNodeChanged(PROPERTY_NAME_IMAGE_INFO);
                setModified(true);
            }
        }
    }

    /**
     * Returns the image information for this raster data node.
     * <p/>
     * <p>The method simply returns the value of <code>ensureValidImageInfo(null, ProgressMonitor.NULL)</code>.
     *
     * @param pm A progress monitor.
     *
     * @return A valid image information instance.
     *
     * @see #getImageInfo(double[],ProgressMonitor)
     * @since BEAM 4.2
     */
    public final ImageInfo getImageInfo(ProgressMonitor pm) {
        return getImageInfo(null, pm);
    }

    /**
     * Gets the image creation information.
     * <p/>
     * <p>If no image information has been assigned before, the <code>{@link #createDefaultImageInfo}</code> method is
     * called with the given parameters passed to this method.
     *
     * @param histoSkipAreas Only used, if new image info is created (see <code>{@link #createDefaultImageInfo}</code>
     *                       method).
     * @param pm             A progress monitor.
     *
     * @return The image creation information.
     *
     * @since BEAM 4.2
     */
    public final synchronized ImageInfo getImageInfo(double[] histoSkipAreas, ProgressMonitor pm) {
        ImageInfo imageInfo = getImageInfo();
        if (imageInfo == null) {
            imageInfo = createDefaultImageInfo(histoSkipAreas, pm);
            setImageInfo(imageInfo, false);
        }
        return imageInfo;
    }

    /**
     * Creates a default image information instance.
     * <p/>
     * <p>An <code>IllegalStateException</code> is thrown in the case that this raster data node has no raster data.
     *
     * @param histoSkipAreas the left (at index 0) and right (at index 1) normalized areas of the raster data
     *                       histogram to be excluded when determining the value range for a linear constrast
     *                       stretching. Can be <code>null</code>, in this case <code>{0.01, 0.04}</code> resp. 5% of
     *                       the entire area is skipped.
     * @param pm             a monitor to inform the user about progress
     *
     * @return a valid image information instance, never <code>null</code>.
     */
    public synchronized ImageInfo createDefaultImageInfo(double[] histoSkipAreas, ProgressMonitor pm) {
        Stx stx = getStx(false, pm);
        Histogram histogram = new Histogram(stx.getHistogramBins(),
                                            stx.getMin(),
                                            stx.getMax());
        return createDefaultImageInfo(histoSkipAreas, histogram);
    }

    /**
     * Creates an instance of a default image information.
     * <p/>
     * <p>An <code>IllegalStateException</code> is thrown in the case that this raster data node has no raster data.
     *
     * @param histoSkipAreas the left (at index 0) and right (at index 1) normalized areas of the raster data
     *                       histogram to be excluded when determining the value range for a linear constrast
     *                       stretching. Can be <code>null</code>, in this case <code>{0.01, 0.04}</code> resp. 5% of
     *                       the entire area is skipped.
     * @param histogram      the histogram to create the image information.
     *
     * @return a valid image information instance, never <code>null</code>.
     */
    public final ImageInfo createDefaultImageInfo(double[] histoSkipAreas, Histogram histogram) {
        final Range range;
        if (histoSkipAreas != null) {
            range = histogram.findRange(histoSkipAreas[0], histoSkipAreas[1], true, false);
        } else {
            range = histogram.findRange(0.01, 0.04, true, false);
        }

        final double min, max;
        if (range.getMin() != range.getMax()) {
            min = scale(range.getMin());
            max = scale(range.getMax());
        } else {
            min = scale(histogram.getMin());
            max = scale(histogram.getMax());
        }

        double center = scale(0.5 * (scaleInverse(min) + scaleInverse(max)));
        final ColorPaletteDef gradationCurve = new ColorPaletteDef(min, center, max);

        return new ImageInfo(gradationCurve);
    }

    /**
     * @return the bitmask overlay info for image display
     */
    public BitmaskOverlayInfo getBitmaskOverlayInfo() {
        return bitmaskOverlayInfo;
    }

    /**
     * Sets the bitmask overlay info for image display
     *
     * @param bitmaskOverlayInfo the bitmask overlay info
     */
    public void setBitmaskOverlayInfo(BitmaskOverlayInfo bitmaskOverlayInfo) {
        if (this.bitmaskOverlayInfo != bitmaskOverlayInfo) {
            this.bitmaskOverlayInfo = bitmaskOverlayInfo;
            fireProductNodeChanged(PROPERTY_NAME_BITMASK_OVERLAY_INFO);
            setModified(true);
        }
    }

    /**
     * Gets all associated bitmask definitions. An empty arry is returned if no bitmask defintions are associated.
     *
     * @return Associated bitmask definitions.
     *
     * @see #getBitmaskOverlayInfo()
     * @see #setBitmaskOverlayInfo(BitmaskOverlayInfo)
     */
    public BitmaskDef[] getBitmaskDefs() {
        final BitmaskOverlayInfo bitmaskOverlayInfo = getBitmaskOverlayInfo();
        if (bitmaskOverlayInfo != null) {
            return bitmaskOverlayInfo.getBitmaskDefs();
        }
        return new BitmaskDef[0];
    }

    /**
     * @return the ROI definition
     */
    public ROIDefinition getROIDefinition() {
        return roiDefinition;
    }

    /**
     * Sets the ROI definition for image display
     *
     * @param roiDefinition the ROI definition
     */
    public void setROIDefinition(ROIDefinition roiDefinition) {
        if (this.roiDefinition != roiDefinition) {
            this.roiDefinition = roiDefinition;
            fireProductNodeChanged(PROPERTY_NAME_ROI_DEFINITION);
            setModified(true);
        }
    }

    /**
     * @return {@code true} if a ROI is usable for this raster data node.
     */
    public boolean isROIUsable() {
        return getROIDefinition() != null && getROIDefinition().isUsable();
    }

    /**
     * Creates an image for this raster data node. The method simply returns <code>ProductUtils.createColorIndexedImage(this,
     * null)</code>.
     *
     * @param pm a monitor to inform the user about progress
     *
     * @return a greyscale/palette-based image for this raster data node
     *
     * @throws IOException if the raster data is not loaded so far and reload causes an I/O error
     * @see #setImageInfo
     */
    public BufferedImage createColorIndexedImage(ProgressMonitor pm) throws IOException {
        return ProductUtils.createColorIndexedImage(this, pm);
    }

    /**
     * Creates an RGB image for this raster data node.
     *
     * @param pm a monitor to inform the user about progress
     *
     * @return a greyscale/palette-based image for this raster data node
     *
     * @throws IOException if the raster data is not loaded so far and reload causes an I/O error
     * @see #setImageInfo
     * @see org.esa.beam.util.ProductUtils#createRgbImage
     */
    public BufferedImage createRgbImage(ProgressMonitor pm) throws IOException {
        if (imageInfo != null) {
            return ProductUtils.createRgbImage(new RasterDataNode[]{this}, imageInfo, pm);
        } else {
            pm.beginTask("Creating image", 1 + 3);
            BufferedImage rgbImage;
            try {
                imageInfo = createDefaultImageInfo(null, SubProgressMonitor.create(pm, 1));
                rgbImage = ProductUtils.createRgbImage(new RasterDataNode[]{this}, imageInfo,
                                                       SubProgressMonitor.create(pm, 3));
            } finally {
                pm.done();
            }
            return rgbImage;
        }
    }

    /**
     * Creates a new ROI from the current ROI definition.
     *
     * @param pm a monitor to inform the user about progress
     *
     * @return a new ROI instance or null if no ROI definition is available
     *
     * @throws java.io.IOException if an I/O error occurs
     * @deprecated since BEAM 4.5, use {@link ImageManager} for imaging.
     */
    @Deprecated
    public synchronized ROI createROI(ProgressMonitor pm) throws IOException {
        final RenderedImage roiMaskImage = ImageManager.getInstance().createRoiMaskImage(this, 0);
        return roiMaskImage != null ? new ROI(roiMaskImage) : null;
    }

    /**
     * Creates a new ROI image for the current ROI definition.
     *
     * @param color the ROI color
     * @param pm    a progress monitor
     *
     * @return a new ROI instance or null if no ROI definition is available
     *
     * @throws java.io.IOException if an I/O error occurs
     * @deprecated since BEAM 4.5, use {@link ImageManager} for imaging.
     */
    @Deprecated
    public synchronized BufferedImage createROIImage(final Color color, ProgressMonitor pm) throws IOException {
        final RenderedImage roiMaskImage = ImageManager.getInstance().createColoredRoiImage(this, color, 0);
        return roiMaskImage != null ? PlanarImage.wrapRenderedImage(roiMaskImage).getAsBufferedImage() : null;
    }

    public byte[] quantizeRasterData(final double newMin, final double newMax, final double gamma,
                                     ProgressMonitor pm) throws IOException {
        final byte[] colorIndexes = new byte[getSceneRasterWidth() * getSceneRasterHeight()];
        quantizeRasterData(newMin, newMax, gamma, colorIndexes, 0, 1, pm);
        return colorIndexes;
    }

    public void quantizeRasterData(double newMin, double newMax, final double gamma, byte[] rgbSamples, int offset,
                                   ProgressMonitor pm) throws IOException {
        quantizeRasterData(newMin, newMax, gamma, rgbSamples, offset, 3, pm);
    }

    public void quantizeRasterData(double newMin, double newMax, double gamma, byte[] samples, int offset, int stride,
                                   ProgressMonitor pm) throws IOException {
        final ProductData sceneRasterData = getSceneRasterData();
        final double rawMin = scaleInverse(newMin);
        final double rawMax = scaleInverse(newMax);
        byte[] gammaCurve = null;
        if (gamma != 0.0 && gamma != 1.0) {
            gammaCurve = MathUtils.createGammaCurve(gamma, new byte[256]);
        }
        if (sceneRasterData != null) {
            quantizeRasterData(sceneRasterData, rawMin, rawMax, samples, offset, stride, gammaCurve, pm);
        } else {
            quantizeRasterDataFromFile(rawMin, rawMax, samples, offset, stride, gammaCurve, pm);
        }
    }

    /**
     * Computes a histogram for the raw raster data contained in this data node within the given value range.
     * <p/>
     * <p/>
     * Note that the histogram computed by this method can significantly differ from the one computed by {@link
     * #computeRasterDataHistogram} if the raster data is {@link #isScalingApplied scaled}. Please also refer to method
     * {@link #isLog10Scaled}.
     *
     * @param roi     an optional ROI, can be null
     * @param numBins the number of bins in the resulting histogram
     * @param range   the value range in which the histogram will be computed
     * @param pm      a monitor to inform the user about progress
     *
     * @return the resulting raw data histogram
     *
     * @throws java.io.IOException if an I/O error occurs
     * @see #isScalingApplied()
     * @deprecated since BEAM 4.5, use {@link org.esa.beam.framework.datamodel.Stx#create(RasterDataNode, java.awt.image.RenderedImage, int, com.bc.ceres.core.ProgressMonitor)}
     */
    @Deprecated
    public Histogram computeRasterDataHistogram(final ROI roi,
                                                final int numBins,
                                                Range range, ProgressMonitor pm) throws IOException {
        Histogram histogram;
        pm.beginTask("Computing histogram for '" + getName() + "'...", range == null ? 2 : 1);
        try {
            if (range == null) {
                range = computeRasterDataRange(roi, SubProgressMonitor.create(pm, 1));
            }
            histogram = Histogram.computeHistogram(getSourceImage(), roi, numBins, range);
            pm.worked(1);
        } finally {
            pm.done();
        }
        return histogram;
    }

    /**
     * Computes a range for the raw raster data contained in this data node within the given value range.
     *
     * @param roi an optional ROI, can be null
     * @param pm  a monitor to inform the user about progress
     *
     * @return the resulting histogram
     *
     * @throws java.io.IOException if an I/O error occurs
     * @see #isScalingApplied()
     * @see #isLog10Scaled()
     * @deprecated since BEAM 4.5, use {@link org.esa.beam.framework.datamodel.Stx#create(RasterDataNode, java.awt.image.RenderedImage, com.bc.ceres.core.ProgressMonitor)}
     */
    @Deprecated
    public Range computeRasterDataRange(final ROI roi, ProgressMonitor pm) throws IOException {
        pm.beginTask("computing range", 1);
        try {
            return Range.computeRange(getSourceImage(), roi);
        } finally {
            pm.done();
        }
    }

    /**
     * Computes statistics for this raster data instance.
     *
     * @param roi on optional ROI, can be <code>null</code>
     * @param pm  a monitor to inform the user about progress
     *
     * @return the statistics
     *
     * @throws java.io.IOException if an I/O error occurs
     * @deprecated since BEAM 4.5, use {@link org.esa.beam.framework.datamodel.Stx#create(RasterDataNode, java.awt.image.RenderedImage, com.bc.ceres.core.ProgressMonitor)}
     */
    @Deprecated
    public Statistics computeStatistics(final ROI roi, ProgressMonitor pm) throws IOException {
        final ProductData rasterData = getRasterData();
        if (rasterData != null) {
            return Statistics.computeStatisticsDouble(new RasterDataDoubleList(rasterData),
                                                      createPixelValidator(0, roi),
                                                      null, pm);
        } else {
            return computeStatisticsFromFile(roi, pm);
        }
    }


    private Statistics computeStatisticsFromFile(final ROI roi, ProgressMonitor pm) throws IOException {
        final LinkedList<Statistics> list = new LinkedList<Statistics>();
        processRasterData("Computing statistics for raster '" + getDisplayName() + "'",
                          new RasterDataProcessor() {
                              public void processRasterDataBuffer(final ProductData buffer, final int y0,
                                                                  final int numLines, ProgressMonitor pm) throws
                                                                                                          IOException {
                                  final RasterDataDoubleList values = new RasterDataDoubleList(buffer);
                                  final IndexValidator pixelValidator = createPixelValidator(y0, roi);
                                  final Statistics statistics = Statistics.computeStatisticsDouble(values,
                                                                                                   pixelValidator,
                                                                                                   null, pm);
                                  list.add(statistics);
                              }
                          }, pm);
        final Statistics[] statisticsArray = list.toArray(new Statistics[list.size()]);
        return Statistics.computeStatistics(statisticsArray, null);
    }

    private void quantizeRasterDataFromFile(final double rawMin,
                                            final double rawMax,
                                            final byte[] samples,
                                            final int offset,
                                            final int stride,
                                            final byte[] gammaCurve, ProgressMonitor pm) throws IOException {
        processRasterData("Quantizing raster '" + getDisplayName() + "'",
                          new RasterDataProcessor() {
                              public void processRasterDataBuffer(ProductData buffer, int y0, int numLines,
                                                                  ProgressMonitor pm) {
                                  int pos = y0 * getRasterWidth() * stride;
                                  quantizeRasterData(buffer, rawMin, rawMax, samples, pos + offset, stride, gammaCurve,
                                                     pm);
                              }
                          }, pm);
    }

    @Deprecated
    protected void processRasterData(String message, RasterDataProcessor processor, ProgressMonitor pm) throws
                                                                                                        IOException {
        Debug.trace("RasterDataNode.processRasterData: " + message);
        int readBufferLineCount = getReadBufferLineCount();
        ProductData readBuffer = null;
        final int width = getRasterWidth();
        final int height = getRasterHeight();
        int numReadsMax = height / readBufferLineCount;
        if (numReadsMax * readBufferLineCount < height) {
            numReadsMax++;
        }
        Debug.trace("RasterDataNode.processRasterData: numReadsMax=" + numReadsMax +
                    ", readBufferLineCount=" + readBufferLineCount);
        pm.beginTask(message, numReadsMax * 2);
        try {
            for (int i = 0; i < numReadsMax; i++) {
                final int y0 = i * readBufferLineCount;
                final int restheight = height - y0;
                final int linesToRead = restheight > readBufferLineCount ? readBufferLineCount : restheight;
                readBuffer = recycleOrCreateBuffer(getDataType(), width * linesToRead, readBuffer);
                readRasterData(0, y0, width, linesToRead, readBuffer, SubProgressMonitor.create(pm, 1));
                processor.processRasterDataBuffer(readBuffer, y0, linesToRead, SubProgressMonitor.create(pm, 1));
                if (pm.isCanceled()) {
                    break;
                }
            }
        } finally {
            pm.done();
        }
        Debug.trace("RasterDataNode.processRasterData: done");
    }

    private static ProductData recycleOrCreateBuffer(final int dataType, final int buffersize, ProductData readBuffer) {
        if (readBuffer == null || readBuffer.getNumElems() != buffersize) {
            readBuffer = ProductData.createInstance(dataType, buffersize);
        }
        return readBuffer;
    }

    /**
     * Creates a validator which can be used to validate indexes of pixels in a flat raster data buffer.
     *
     * @param lineOffset the absolute line offset, zero based
     * @param roi        an optional ROI
     *
     * @return a new validator instance, never null
     *
     * @throws IOException if an I/O error occurs
     */
    public IndexValidator createPixelValidator(int lineOffset, final ROI roi) throws IOException {
        if (isValidMaskUsed() && roi != null) {
            return new DelegatingValidator(
                    new RoiValidator(rasterWidth, lineOffset, getValidMaskROI()),
                    new RoiValidator(rasterWidth, lineOffset, roi));
        } else if (isValidMaskUsed()) {
            return new RoiValidator(rasterWidth, lineOffset, getValidMaskROI());
        } else if (roi != null) {
            return new RoiValidator(rasterWidth, lineOffset, roi);
        } else {
            return IndexValidator.TRUE;
        }
    }


    /**
     * Applies the scaling <code>v * scalingFactor + scalingOffset</code> the the given input value. If the
     * <code>log10Scaled</code> property is true, the result is taken to the power of 10 <i>after</i> the actual
     * scaling.
     *
     * @param v the input value
     *
     * @return the scaled value
     */
    public final double scale(double v) {
        v = v * scalingFactor + scalingOffset;
        if (log10Scaled) {
            v = Math.pow(10.0, v);
        }
        return v;
    }

    /**
     * Applies the inverse scaling <code>(v - scalingOffset) / scalingFactor</code> the the given input value. If the
     * <code>log10Scaled</code> property is true, the common logarithm is applied to the input <i>before</i> the actual
     * scaling.
     *
     * @param v the input value
     *
     * @return the scaled value
     */
    public final double scaleInverse(double v) {
        if (log10Scaled) {
            v = Math.log10(v);
        }
        return (v - scalingOffset) / scalingFactor;
    }


    private void setScalingApplied() {
        scalingApplied = getScalingFactor() != 1.0
                         || getScalingOffset() != 0.0
                         || isLog10Scaled();
    }

    /**
     * Returns the pixel located at (x,y) as a string value.
     *
     * @param x the X co-ordinate of the pixel location
     * @param y the Y co-ordinate of the pixel location
     *
     * @return the pixel value at (x,y) as string or an error message text
     */
    public String getPixelString(int x, int y) {
        if (!isPixelInRange(x, y)) {
            return INVALID_POS_TEXT;
        }
        if (hasRasterData()) {
            try {
                ensureValidMaskComputed(ProgressMonitor.NULL);
            } catch (IOException e) {
                return IO_ERROR_TEXT;
            }
            if (isPixelValid(x, y)) {
                if (isFloatingPointType()) {
                    return String.valueOf(getPixelFloat(x, y));
                } else {
                    return String.valueOf(getPixelInt(x, y));
                }
            } else {
                return NO_DATA_TEXT;
            }
        } else {
            try {
                final boolean pixelValid = readValidMask(x, y, 1, 1, new boolean[1])[0];
                if (pixelValid) {
                    if (isFloatingPointType()) {
                        final float[] pixel = readPixels(x, y, 1, 1, new float[1], ProgressMonitor.NULL);
                        return String.valueOf(pixel[0]);
                    } else {
                        final int[] pixel = readPixels(x, y, 1, 1, new int[1], ProgressMonitor.NULL);
                        return String.valueOf(pixel[0]);
                    }
                } else {
                    return NO_DATA_TEXT;
                }
            } catch (IOException e) {
                return IO_ERROR_TEXT;
            }
        }
    }

    private boolean isPixelInRange(int x, int y) {
        return x >= 0 && y >= 0 && x < getSceneRasterWidth() && y < getSceneRasterHeight();
    }

    private boolean isValidPixelExpressionSet() {
        return getValidPixelExpression() != null && getValidPixelExpression().trim().length() > 0;
    }

    private int getReadBufferLineCount() {
        final int sizePerLine = getRasterWidth() * ProductData.getElemSize(getDataType());
        int bufferLineCount = READ_BUFFER_MAX_SIZE / sizePerLine;
        if (bufferLineCount == 0) {
            bufferLineCount = 1;
        }
        return bufferLineCount;
    }

    private static void quantizeRasterData(final ProductData sceneRasterData, final double rawMin, final double rawMax,
                                           byte[] samples, int offset, int stride, byte[] resampleLUT,
                                           ProgressMonitor pm) {
        Quantizer.quantizeGeneric(sceneRasterData.getElems(), sceneRasterData.isUnsigned(), rawMin, rawMax, samples,
                                  offset, stride, pm);
        if (resampleLUT != null && resampleLUT.length == 256) {
            for (int i = 0; i < samples.length; i++) {
                samples[i] = resampleLUT[samples[i] & 0xff];
            }
        }
    }

    private void setGeophysicalNoDataValue() {
        geophysicalNoDataValue = scale(getNoDataValue());
    }

    /**
     * Returns whether the source image is set on this {@code RasterDataNode}.
     *
     * @return whether the source image is set.
     *
     * @since BEAM 4.5
     */
    public boolean isSourceImageSet() {
        return sourceImage != null;
    }

    /**
     * Gets the source image associated with this {@code RasterDataNode}.
     *
     * @return The source image.
     *
     * @since BEAM 4.2
     */
    public MultiLevelImage getSourceImage() {
        if (!isSourceImageSet()) {
            synchronized (this) {
                if (!isSourceImageSet()) {
                    this.sourceImage = toMultiLevelImage(createSourceImage());
                }
            }
        }
        return sourceImage;
    }

    /**
     * Creates the source image associated with this {@code RasterDataNode}. This shall preferably be a {@link MultiLevelImage} instance.
     *
     * @return The source image.
     *
     * @since BEAM 4.5
     */
    protected abstract RenderedImage createSourceImage();

    /**
     * Sets the source image associated with this {@code RasterDataNode}.
     *
     * @param sourceImage The source image.
     *
     * @since BEAM 4.2
     */
    public synchronized void setSourceImage(RenderedImage sourceImage) {
        setSourceImage(toMultiLevelImage(sourceImage));
    }

    /**
     * Sets the source image associated with this {@code RasterDataNode}.
     *
     * @param sourceImage The source image.
     *
     * @since BEAM 4.6
     */
    public synchronized void setSourceImage(MultiLevelImage sourceImage) {
        final RenderedImage oldValue = this.sourceImage;
        if (oldValue != sourceImage) {
            this.sourceImage = sourceImage;
            resetGeophysicalImage();
            fireProductNodeChanged("sourceImage", oldValue);
        }
    }

    /**
     * Returns whether the geophysical image is set on this {@code RasterDataNode}.
     * <p/>
     * This method belongs to preliminary API and may be removed or changed in the future.
     *
     * @return whether the geophysical image is set.
     *
     * @since BEAM 4.6
     */
    public boolean isGeophysicalImageSet() {
        return geophysicalImage != null;
    }

    /**
     * @return The geophysical source image.
     *
     * @since BEAM 4.5
     */
    public MultiLevelImage getGeophysicalImage() {
        if (geophysicalImage == null) {
            synchronized (this) {
                if (geophysicalImage == null) {
                    if (isScalingApplied()) {
                        this.geophysicalImage = createGeophysicalImage();
                    } else {
                        this.geophysicalImage = getSourceImage();
                    }
                }
            }
        }
        return geophysicalImage;
    }

    private MultiLevelImage createGeophysicalImage() {
        return new DefaultMultiLevelImage(new GenericMultiLevelSource(getSourceImage()) {

            @Override
            protected RenderedImage createImage(RenderedImage[] sourceImages, int level) {
                return ImageManager.createRescaleOp(sourceImages[0],
                                                    ImageManager.getDataBufferType(getGeophysicalDataType()),
                                                    getScalingFactor(),
                                                    getScalingOffset(),
                                                    isLog10Scaled());
            }
        });
    }

    /**
     * @param geophysicalImage The geophysical source image.
     *
     * @since BEAM 4.5
     * @deprecated since BEAM 4.6, the geophysicalcal source image is created internally based on the {@link org.esa.beam.framework.datamodel.Scaling} of this band.
     */
    @Deprecated
    public synchronized void setGeophysicalImage(RenderedImage geophysicalImage) {
        setGeophysicalImage(toMultiLevelImage(geophysicalImage));
    }

    /**
     * @param geophysicalImage The geophysical source image.
     *
     * @since BEAM 4.5
     * @deprecated since BEAM 4.6, the geophysicalcal source image is created internally based on the {@link org.esa.beam.framework.datamodel.Scaling} of this band.
     */
    @Deprecated
    public synchronized void setGeophysicalImage(MultiLevelImage geophysicalImage) {
        final MultiLevelImage oldValue = this.geophysicalImage;
        if (oldValue != geophysicalImage) {
            if (this.geophysicalImage != null) {
                this.geophysicalImage.dispose();
            }
            this.geophysicalImage = geophysicalImage;
            fireProductNodeChanged("geophysicalImage", oldValue);
        }
    }

    private void resetGeophysicalImage() {
        geophysicalImage = null;
    }

    /**
     * Returns wether the valid mask image is set on this {@code RasterDataNode}.
     *
     * @return Wether the source image is set.
     *
     * @since BEAM 4.5
     */
    public boolean isValidMaskImageSet() {
        return validMaskImage != null;
    }

    /**
     * Gets the valid-mask image associated with this {@code RasterDataNode}.
     *
     * @return The rendered image.
     *
     * @since BEAM 4.2
     */
    public MultiLevelImage getValidMaskImage() {
        if (!isValidMaskImageSet() && isValidMaskUsed()) {
            synchronized (this) {
                if (!isValidMaskImageSet() && isValidMaskUsed()) {
                    validMaskImage = ImageManager.getInstance().createValidMaskMultiLevelImage(this);
                }
            }
        }
        return validMaskImage;
    }

    /**
     * Sets the valid-mask image associated with this {@code RasterDataNode}.
     *
     * @param image The rendered image.
     *
     * @since BEAM 4.2
     * @deprecated since BEAM 4.6, valid-mask images are created internally based on the {@link #getValidMaskExpression()}.
     */
    @Deprecated
    public synchronized void setValidMaskImage(RenderedImage image) {
        setValidMaskImage(toMultiLevelImage(image));
    }

    /**
     * Sets the valid-mask image associated with this {@code RasterDataNode}.
     *
     * @param validMaskImage The valid-mask image.
     *
     * @since BEAM 4.6
     */
    private synchronized void setValidMaskImage(MultiLevelImage validMaskImage) {
        final RenderedImage oldValue = this.validMaskImage;
        if (oldValue != validMaskImage) {
            this.validMaskImage.dispose();
            this.validMaskImage = validMaskImage;
            validMaskROI = null;
            fireProductNodeChanged("validMaskImage", oldValue);
        }
    }

    public synchronized boolean isStxSet() {
        return stx != null;
    }

    /**
     * Returns a ROI for the validMask, if the a validMask is used.
     * Check before calling this method if a validMask is used.
     *
     * @return the ROI for the valid mask
     */
    private synchronized ROI getValidMaskROI() {
        if (validMaskROI == null) {
            synchronized (this) {
                if (validMaskROI == null) {
                    validMaskROI = new ROI(getValidMaskImage());
                }
            }
        }
        return validMaskROI;
    }

    /**
     * Gets the statistics. If statistcs are not yet available,
     * the method will compute (possibly inaccurate) statistics and return those.
     * <p/>
     * If accurate statistics are required, the {@link #getStx(boolean, com.bc.ceres.core.ProgressMonitor)}
     * shall be used instead.
     * <p/>
     * This method belongs to preliminary API and may be removed or changed in the future.
     *
     * @return The statistics.
     *
     * @see #getStx(boolean, com.bc.ceres.core.ProgressMonitor)
     * @see #setStx(Stx)
     * @since BEAM 4.2, revised in BEAM 4.5
     */
    public Stx getStx() {
        if (stx == null) {
            synchronized (this) {
                if (stx == null) {
                    getStx(false, ProgressMonitor.NULL);
                }
            }
        }
        return stx;
    }


    /**
     * Gets the statistics.
     * If the statistics have not been set before they are computed using the given progress monitor {@code pm} and then set.
     * This method belongs to preliminary API and may be removed or changed in the future.
     *
     * @param accurate If true, accurate statistics are computed.
     * @param pm       A progress monitor which is used to compute the new statistics, if required.
     *
     * @return The statistics.
     *
     * @since since BEAM 4.5
     */
    public synchronized Stx getStx(boolean accurate, ProgressMonitor pm) {
        if (stx == null || stx.getResolutionLevel() > 0 && accurate) {
            if (accurate) {
                setStx(computeStxImpl(0, pm));
            } else {
                final int levelCount = getSourceImage().getModel().getLevelCount();
                final int statisticsLevel = ImageManager.getInstance().getStatisticsLevel(this, levelCount);
                setStx(computeStxImpl(statisticsLevel, pm));
            }
        }
        return stx;
    }

    /**
     * Sets the statistics. It is the responsibility of the caller to ensure that the given statistics
     * are really related to this {@code RasterDataNode}'s raster data.
     * The method fires a property change event for the property {@link #PROPERTY_NAME_STX}.
     * This method belongs to preliminary API and may be removed or changed in the future.
     *
     * @param stx The statistics.
     *
     * @since BEAM 4.2, revised in BEAM 4.5
     */
    public synchronized void setStx(Stx stx) {
        final Stx oldValue = this.stx;
        if (oldValue != stx) {
            this.stx = stx;
            fireProductNodeChanged(PROPERTY_NAME_STX, oldValue);
        }
    }


    /**
     * Computes the statistics. May be overridden.
     * This method belongs to preliminary API and may be removed or changed in the future.
     *
     * @param level The resolution level.
     * @param pm    A progress monitor.
     *
     * @return The statistics.
     *
     * @since BEAM 4.5
     */
    protected Stx computeStxImpl(int level, ProgressMonitor pm) {
        return Stx.create(this, level, pm);
    }

    @Deprecated
    public static interface RasterDataProcessor {

        void processRasterDataBuffer(ProductData buffer, int y0, int numLines, ProgressMonitor pm) throws IOException;
    }

    final static class DelegatingValidator implements IndexValidator {

        private final IndexValidator validator1;
        private final IndexValidator validator2;

        public DelegatingValidator(IndexValidator validator1, IndexValidator validator2) {
            this.validator1 = validator1;
            this.validator2 = validator2;
        }

        public boolean validateIndex(int pixelIndex) {
            return validator1.validateIndex(pixelIndex)
                   && validator2.validateIndex(pixelIndex);
        }
    }

    final static class ValidMaskValidator implements IndexValidator {

        private final int pixelOffset;
        private final BitRaster validMask;

        public ValidMaskValidator(int rasterWidth, int lineOffset, BitRaster validMask) {
            this.pixelOffset = rasterWidth * lineOffset;
            this.validMask = validMask;
        }

        public final boolean validateIndex(final int pixelIndex) {
            return validMask.isSet(pixelOffset + pixelIndex);
        }
    }

    final static class RoiValidator implements IndexValidator {

        private final int rasterWidth;
        private final int lineOffset;
        private final ROI roi;

        public RoiValidator(int rasterWidth, int lineOffset, ROI roi) {
            this.rasterWidth = rasterWidth;
            this.lineOffset = lineOffset;
            this.roi = roi;
        }

        public boolean validateIndex(int pixelIndex) {
            final int x = pixelIndex % rasterWidth;
            final int y = lineOffset + pixelIndex / rasterWidth;
            return roi.contains(x, y);
        }
    }

    /**
     * Adapts a  {@link org.esa.beam.util.math.DoubleList}
     */
    public class RasterDataDoubleList implements DoubleList {

        private final ProductData _buffer;

        public RasterDataDoubleList(ProductData buffer) {
            _buffer = buffer;
        }

        public final int getSize() {
            return _buffer.getNumElems();
        }

        public final double getDouble(int index) {
            return scale(_buffer.getElemDoubleAt(index));
        }
    }

    /////////////////////////////////////////////////////////////////////////
    // Deprecated API

    /**
     * @deprecated in BEAM 4.1, no replacement
     */
    @Deprecated
    private byte[] dataMask;


    /**
     * @deprecated in BEAM 4.1, use {@link #isValidMaskUsed()}
     */
    @Deprecated
    public boolean isDataMaskUsed() {
        return isValidMaskUsed();
    }

    /**
     * @deprecated in BEAM 4.1, use {@link #getValidMask()}
     */
    @Deprecated
    public byte[] getDataMask() {
        return dataMask;
    }

    /**
     * @deprecated in BEAM 4.1, use {@link #setValidMask(org.esa.beam.util.BitRaster)}
     */
    @Deprecated
    protected void setDataMask(final byte[] dataMask) {
        this.dataMask = dataMask;
    }

    /**
     * @deprecated in BEAM 4.1, use {@link #ensureValidMaskComputed(com.bc.ceres.core.ProgressMonitor)}
     */
    @Deprecated
    public void ensureDataMaskIsAvailable() throws IOException {
        ensureValidMaskComputed(ProgressMonitor.NULL);
    }

    /**
     * @deprecated in BEAM 4.1, use {@link #computeValidMask}
     */
    @Deprecated
    protected synchronized void computeDataMask() throws IOException {
        computeValidMask(ProgressMonitor.NULL);
    }

    /**
     * @deprecated in BEAM 4.1, use {@link RasterDataNode#createPixelValidator(int,javax.media.jai.ROI)}
     */
    @Deprecated
    public class PixelValidator implements IndexValidator {

        private final int _y0;
        private final ROI _roi;

        /**
         * Creates a new pixel index validator.
         *
         * @param y0  the line offset, zero based
         * @param roi the roi, may be null
         */
        public PixelValidator(int y0, ROI roi) {
            _y0 = y0;
            _roi = roi;
        }

        public final boolean validateIndex(final int index) {
            final int w = getSceneRasterWidth();
            return isPixelValid(index % w, _y0 + index / w, _roi);
        }
    }

    /**
     * @deprecated since BEAM 4.2, use {@link #getImageInfo(double[],ProgressMonitor)}
     */
    @Deprecated
    public ImageInfo ensureValidImageInfo(double[] histoSkipAreas, boolean ignoreInvalidZero, ProgressMonitor pm) throws
                                                                                                                  IOException {
        return getImageInfo(histoSkipAreas, pm);
    }

    /**
     * @deprecated since BEAM 4.2, use {@link #getImageInfo(double[],ProgressMonitor)}
     */
    @Deprecated
    public ImageInfo createDefaultImageInfo(double[] histoSkipAreas, boolean ignoreInvalidZero,
                                            ProgressMonitor pm) throws IOException {
        return createDefaultImageInfo(histoSkipAreas, pm);
    }

    /**
     * @deprecated since BEAM 4.2, use {@link #createDefaultImageInfo(double[], org.esa.beam.util.math.Histogram)}
     */
    @Deprecated
    public ImageInfo createDefaultImageInfo(double[] histoSkipAreas, Histogram histogram, boolean ignoreInvalidZero) {
        return createDefaultImageInfo(histoSkipAreas, histogram);
    }

    /**
     * @deprecated since 4.1. Don't use this.
     */
    @Deprecated
    @Override
    protected void additionalNameCheck(String trimmedName) {
        final Product product = getProduct();
        if (product != null && product.containsRasterDataNode(trimmedName)) {
            throw new IllegalArgumentException("The product '" + product.getName() + "' already contains " +
                                               "a raster data node with the name '" + trimmedName + "'.");
        }
    }

    /**
     * @return The expression used for the computation of the mask which identifies valid pixel values,
     *         or {@code null}.
     *
     * @deprecated since BEAM 4.2, use {@link #getValidMaskExpression()} instead
     */
    @Deprecated
    public String getDataMaskExpression() {
        return getValidMaskExpression();
    }

    /**
     * Returns the image information for this raster data node.
     * <p/>
     * <p>The method simply returns the value of <code>ensureValidImageInfo(null, ProgressMonitor.NULL)</code>.
     *
     * @return a valid image information instance.
     *
     * @throws IOException if an I/O error occurs
     * @deprecated since BEAM 4.2, use {@link #getImageInfo(com.bc.ceres.core.ProgressMonitor)}
     */
    @Deprecated
    public final ImageInfo ensureValidImageInfo() throws IOException {
        return getImageInfo(ProgressMonitor.NULL);
    }

    /**
     * Ensures that this raster data node has valid image information and returns it.
     * <p/>
     * <p>If no image information has been assigned before, the <code>{@link #createDefaultImageInfo}</code> method is
     * called with the given parameters passed to this method.
     *
     * @param histoSkipAreas only used, if new image info is created (see <code>{@link #createDefaultImageInfo}</code>
     *                       method)
     * @param pm             a progress monitor
     *
     * @return a valid image information instance, never <code>null</code>.
     *
     * @throws IOException if an I/O error occurs
     * @deprecated since BEAM 4.2, use {@link #getImageInfo(double[], com.bc.ceres.core.ProgressMonitor)}
     */
    @Deprecated
    public final ImageInfo ensureValidImageInfo(double[] histoSkipAreas, ProgressMonitor pm) throws IOException {
        return getImageInfo(histoSkipAreas, pm);
    }


    private static MultiLevelImage toMultiLevelImage(RenderedImage sourceImage) {
        if (sourceImage instanceof MultiLevelImage) {
            return (MultiLevelImage) sourceImage;
        } else {
            return new DefaultMultiLevelImage(ImageManager.getMultiLevelSource(sourceImage));
        }
    }

}
