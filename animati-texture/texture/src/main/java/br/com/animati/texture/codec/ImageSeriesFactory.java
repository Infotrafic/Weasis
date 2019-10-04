/*
 * @copyright Copyright (c) 2012 Animati Sistemas de Informática Ltda. (http://www.animati.com.br)
 */
package br.com.animati.texture.codec;

import br.com.animati.texture.codec.loader.DataBytesMath;
import br.com.animati.texture.codec.loader.GeometryLoaderMath;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.vecmath.Vector3d;

import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.ImageElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.SoftHashMap;
import org.weasis.core.api.media.data.TagW;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.SortSeriesStack;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.utils.LutParameters;
import org.weasis.dicom.explorer.wado.SeriesInstanceList;
import org.weasis.opencv.data.ImageCV;
import org.weasis.opencv.data.LookupTableCV;
import org.weasis.opencv.data.PlanarImage;
import org.weasis.opencv.op.ImageConversion;

import br.com.animati.texturedicom.ImageSeries;
import br.com.animati.texturedicom.ImageSeriesSliceAddedListener;
import br.com.animati.texturedicom.TextureData;

/**
 * Builds the texturedicom ImageSeries.
 *
 * Orientation Tags: - ImageOrientationPatient (0020,0037): double[] lenth=6 - ImageOrientationPlane: label, generated
 * using ImageOrientationPatient: The method is ImageOrientation.makeImageOrientationLabelFromImageOrientationPatient,
 * and "allow some deviation" on Axial, Sagital, Coronal; Oblique can mean anything! So, even if ImageOrientationPlane
 * is a splitting rule, there is no warranty to have consistent ImageOrientationPatient.
 *
 * Location Tags: - SliceLocation (0020, 1041) : float (dz) - ImagePositionPatient (0020,0032) : double[] lenth=3 (dx,
 * dy, dz) - SlicePosition (weasis-generated, @see DicomMediaUtils.computeSlicePositionVector) Uses ImagePositionPatient
 * (0020,0032) & ImageOrientationPatient (0020,0037). It's the value used to calculate distance between frames for the
 * 3-D model.
 *
 *
 * @author Gabriela Bauermann (gabriela@animati.com.br)
 * @version 2013, 24 jul
 */
public class ImageSeriesFactory {

    /** Logger. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageSeriesFactory.class);

    /** Max pixel value, assuming 16-bits. */
    public static final int MAX_16 = (int) Math.pow(2, 16);

    /* Event names: */
    /** Texture of newValue series has been replaced. */
    public static final String TEXTURE_REPLACED = "texture.replaced";
    /** A hint to display. */
    public static final String TEXTURE_DO_DISPLAY = "texture.doDisplay";
    /** Series is completely loaded. */
    public static final String TEXTURE_LOAD_COMPLETE = "texture.loadComplete";
    /** Can't create a Valid ImageSeries. */
    public static final String TEXTURE_ERROR = "texture.error";

    private static final SoftHashMap<MediaSeries, TextureDicomSeries> texCache =
        new SoftHashMap<>();
    private static final ImageSeriesSliceAddedListener addSliceListener = new ImageSeriesSliceAddedListener() {

        @Override
        public void onSliceAdded(ImageSeries imageSeries, int sliceIndex, int glErrorCode, String glErrorMessage) {
            if (glErrorCode != 0) {
                String msg = "Error trying to add " + sliceIndex + ": " + glErrorMessage;
                LOGGER.error(msg);
                if (imageSeries instanceof TextureDicomSeries) {
                    ((TextureDicomSeries) imageSeries).textureLogInfo.writeText(msg);
                }
            } else {
                if (imageSeries instanceof TextureDicomSeries) {
                    ((TextureDicomSeries) imageSeries).setInVideo(sliceIndex, true);
                }
            }

        }
    };

    /** Register when series was sent to here to build texture. */
    private long timeStarted;

    private Comparator comparator;

    private static PropertyChangeSupport pcSupport;

    public static void removeFromCache(TextureDicomSeries texture) {
        texCache.remove(texture.getSeries());
    }

    public ImageSeriesFactory() {
        if (pcSupport == null) {
            pcSupport = new PropertyChangeSupport(this);
        }
    }

    public TextureDicomSeries createImageSeries(final MediaSeries series) throws Exception {
        return this.createImageSeries(series, SortSeriesStack.slicePosition, false);
    }

    /**
     * Creates or recovers a texture from cache.
     *
     * If it comes from cache, its factory may be resumed, if needed.
     *
     * @param series
     * @param sorter
     * @param force
     * @return
     * @throws Exception
     */
    public TextureDicomSeries createImageSeries(final MediaSeries series, Comparator sorter, final boolean force)
        throws Exception {

        if (sorter == null) {
            comparator = SortSeriesStack.slicePosition;
        } else {
            comparator = sorter;
        }

        TextureDicomSeries imSeries = texCache.get(series);
        boolean changed = false;
        if (force || imSeries == null || imSeries.getSeriesSorter() != comparator) {

            // Start counting time to log texture build time information.
            timeStarted = System.currentTimeMillis();

            // Discard old
            if (imSeries != null) {
                imSeries.interruptFactory();
                imSeries.discardTexture();
                changed = true;
            }

            String log1 = "Building texture from series: " + TagD.getTagValue(series, Tag.SeriesDescription) + " / "
                + TagD.getTagValue(series, Tag.SeriesInstanceUID) + " / sorded by " + comparator;
            LOGGER.info(log1);

            final int dims[] = getDimentions(series);
            String log2 = "Dimensions: w = " + dims[0] + " h = " + dims[1] + " d = " + dims[2];
            LOGGER.info(log2);

            final TextureData.Format imageDataFormat = getImageDataFormat(series);
            String log3 = "DataFormat by tag: " + imageDataFormat;
            LOGGER.info(log3);

            if (imageDataFormat == null) {
                throw new IllegalArgumentException("Invalid data format.");
            }

            imSeries = new TextureDicomSeries(dims[0], dims[1], dims[2], imageDataFormat, series, comparator);

            imSeries.textureLogInfo.writeText(log1);
            imSeries.textureLogInfo.writeText(log2);
            imSeries.textureLogInfo.writeText(log3);

            executeLoader(imSeries, series);

            texCache.put(series, imSeries);
            if (changed) {
                fireProperyChange(this, TEXTURE_REPLACED, imSeries);
            }
        } else {
            LOGGER.info("Returning from cache: {}", TagD.getTagValue(imSeries, Tag.SeriesDescription));

            if (!imSeries.isAllInVideo() && imSeries.isFactoryDone()) {
                if (series.getSeriesLoader() == null && (imSeries.getSliceCount() != series.size(null))) {
                    return createImageSeries(series, sorter, true);
                } else {
                    timeStarted = System.currentTimeMillis();
                    executeLoader(imSeries, series);
                }
            }
        }
        return imSeries;
    }

    /**
     * Call the safer loader for the given series.
     *
     * LoaderThread: only one that will work if sorter!=instanceNumber. Its also safer if series has ben splitted.
     *
     * WadoSafeLoader: gives the correct result if series is still downloading, and random placed images are still
     * missing. Dont works if sorter!=instanceNumber and may go wrong if series has been slitted.
     *
     * @param imSeries
     *            texture
     * @param series
     *            mediaSeries
     */
    private void executeLoader(TextureDicomSeries imSeries, MediaSeries series) {
        if (SortSeriesStack.instanceNumber == comparator && series.getSeriesLoader() != null
            && (series.getTagValue(TagW.SplitSeriesNumber) == null)) {
            try {
                imSeries.textureLogInfo.writeText("Using Wado-safe factory.");
                LoaderThread thread = new WadoSafeLoader(imSeries, series);
                thread.execute();
                imSeries.setFactorySW(thread);
                imSeries.isToCountObjects = false;
            } catch (IllegalArgumentException ex) {
                LOGGER.warn(ex.getMessage());
                // start again
                imSeries.textureLogInfo.writeText("Using SingleThread factory.");
                LoaderThread thread = new LoaderThread(imSeries, series);
                thread.execute();
                imSeries.setFactorySW(thread);
                imSeries.isToCountObjects = false;
            }

        } else {
            // Inicia thread de envio para a placa de video (one thread)
            imSeries.textureLogInfo.writeText("Using SingleThread factory.");
            LoaderThread thread = new LoaderThread(imSeries, series);
            thread.execute();
            imSeries.setFactorySW(thread);
            imSeries.isToCountObjects = true;
        }
    }

    public static void addPropertyChangeListener(PropertyChangeListener listener) {
        if (pcSupport == null) {
            pcSupport = new PropertyChangeSupport(new ImageSeriesFactory());
        }
        pcSupport.addPropertyChangeListener(listener);
    }

    public static void removePropertyChangeListener(PropertyChangeListener listener) {
        if (pcSupport != null) {
            pcSupport.removePropertyChangeListener(listener);
        }

    }

    protected static void fireProperyChange(final Object source, final String name, final Object newValue) {

        final PropertyChangeEvent event = new PropertyChangeEvent(source, name, null, newValue);

        if (pcSupport != null) {
            if (SwingUtilities.isEventDispatchThread()) {
                pcSupport.firePropertyChange(event);
            } else {
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        pcSupport.firePropertyChange(event);
                    }
                });
            }
        }
    }

    private static void feedInValues(final DicomImageElement elmt, final TextureDicomSeries imSeries,
        ArrayList<double[]> windowValues, ArrayList<double[]> levelValues) {

        // values of inValues based on DicomImageUtils::setWindowLevelLinearLut

        // ActionW.IMAGE_PIX_PADDING is true by default, so we just use true for now.
        boolean pixelPadding = true;

        int minValue = (int) elmt.getMinValue(null, pixelPadding);
        int maxValue = (int) elmt.getMaxValue(null, pixelPadding);

        int minInValue = Math.min(maxValue, minValue);
        int maxInValue = Math.max(maxValue, minValue);

        if (minInValue < imSeries.windowingMinInValue) {
            imSeries.windowingMinInValue = minInValue;
        }
        if (maxInValue > imSeries.windowingMaxInValue) {
            imSeries.windowingMaxInValue = maxInValue;
        }

        double[] tempListWindow = TagD.getTagValue(elmt, Tag.WindowWidth, double[].class);
        double[] tempListLevel = TagD.getTagValue(elmt, Tag.WindowCenter, double[].class);
        if (tempListLevel != null && tempListWindow != null) {
            windowValues.add(tempListWindow);
            levelValues.add(tempListLevel);
        }

        if (levelValues != null && windowValues != null && !levelValues.isEmpty() && !windowValues.isEmpty()) {
            double[] listOfLevelValues = getHigherOccurrenceOfValues(levelValues, levelValues.get(0).length);
            double[] listOfWindowValues = getHigherOccurrenceOfValues(windowValues, windowValues.get(0).length);

            imSeries.setTag(TagD.get(Tag.WindowWidth), listOfWindowValues);
            imSeries.setTag(TagD.get(Tag.WindowCenter), listOfLevelValues);
        }

    }

    private static void sendError(ErrorCode error, TextureDicomSeries imSeries) {
        FormattedException ex = new FormattedException(error.getCode(), error.getLogMessage(), error.getUserMessage());
        imSeries.interruptFactory();

        fireProperyChange(imSeries, TEXTURE_ERROR, ex);
        LOGGER.error("Error code: {} - {}", ex.getErrorCode() , ex.getLogMessage());
    }

    /**
     * Get series dimensions (width and height from tags or from first image, depth from slice count - series.size(null)
     * )).
     *
     * @param series
     *            Series to get dimensions from.
     * @return { width, height, depth }
     */
    private static int[] getDimentions(final MediaSeries series) throws IllegalStateException {

        int[] dims = new int[3];
        Object tagValue = series.getTagValue(TagW.ImageWidth);
        Object tagValueH = series.getTagValue(TagW.ImageHeight);

        if (tagValue instanceof Integer && tagValueH instanceof Integer) {
            dims[0] = (Integer) tagValue;
            dims[1] = (Integer) tagValueH;
        } else {
            Iterator iterator = series.getMedias(null, null).iterator();
            boolean foundDims = false;
            while (iterator.hasNext() && !foundDims) {
                Object next = iterator.next();
                if (next instanceof ImageElement) {
                    ImageElement imgElement = (ImageElement) next;
                    final PlanarImage image = imgElement.getImage();
                    if (imgElement.isReadable() && image != null) {
                        dims[0] = image.width();
                        dims[1] = image.height();
                        foundDims = true;
                    }
                } else {
                    LOGGER.info("found next not ImageElement: " + next);
                }
            }

            if (!foundDims) {
                throw new IllegalStateException("Impossible to calculate dimensions:" + " no readable image found.");
            }
        }

        // Num of images:
        SeriesInstanceList seriesInstanceList = (SeriesInstanceList) series.getTagValue(TagW.WadoInstanceReferenceList);
        if (seriesInstanceList != null) {
            dims[2] = seriesInstanceList.size();
            if (dims[2] < series.size(null)) {
                dims[2] = series.size(null);
            }
        } else {
            dims[2] = series.size(null);
        }
        return dims;
    }

    /**
     * Find image data format.
     *
     * @param origin
     *            Series to get data-format from.
     * @return Data format flag.
     */
    private static TextureData.Format getImageDataFormat(final MediaSeries origin) {

        Object media = origin.getMedia(MediaSeries.MEDIA_POSITION.FIRST, null, null);

        if (media instanceof DicomImageElement) {
            DicomImageElement imgElement = (DicomImageElement) media;
            final LookupTableCV mLUTSeq = (LookupTableCV) imgElement.getTagValue(TagW.ModalityLUTData);
            LutParameters lutparams = imgElement.getLutParameters(null, true, mLUTSeq, false);

            int bitsOutput = lutparams == null ? imgElement.getBitsStored():lutparams.getBitsOutput() ;
            boolean isSigned = lutparams == null ? imgElement.isPixelRepresentationSigned():lutparams.isOutputSigned();

            if (bitsOutput > 8 && bitsOutput <= 16) {
                return isSigned ? TextureData.Format.SignedShort : TextureData.Format.UnsignedShort;
            }

            if (bitsOutput <= 8) {
                Integer tagValue = TagD.getTagValue(imgElement, Tag.SamplesPerPixel, Integer.class);
                if (tagValue != null && tagValue > 1) {
                    // Color images has "Samples Per Pixel" equal to 3
                    if (tagValue == 3) {
                        return TextureData.Format.RGB8;
                    }
                    return null;
                }
                return TextureData.Format.Byte;
            }
        }
        return null;
    }

    /**
     * Takes a BufferedImage and sends the bytes to the ImageSeries object.
     *
     * @param place
     *            Relative location of the image plane on volume.
     * @param image
     *            The image.
     * @param imgSeries
     *            ImageSeries to receive the bytes data.
     * @return the buffer class found.
     */
    public static String putBytesInImageSeries(final int place, final BufferedImage image,
        final TextureDicomSeries imgSeries) {

        String bufferClass = ".";
        if (imgSeries != null && image != null) {
            final DataBuffer dataBuffer = image.getRaster().getDataBuffer();
            byte[] bytesOut = null;
            if (dataBuffer instanceof DataBufferByte) {
                bufferClass = "DataBufferByte";
                bytesOut = DataBytesMath.bufferBytetoBytesArray((DataBufferByte) dataBuffer);
            } else if (dataBuffer instanceof DataBufferShort || dataBuffer instanceof DataBufferUShort) {
                bufferClass = dataBuffer.getClass().getSimpleName();
                short[] data = getShortDataFromBuffer(dataBuffer);
                if (TextureData.Format.Byte.equals(imgSeries.getTextureData().getFormat())) {
                    bytesOut = DataBytesMath.short8ArrayToByteArray(data);
                } else {
                    bytesOut = DataBytesMath.shortArrayToByteArray(data);
                }

            }
            if (bytesOut != null) {
                if (imgSeries.getTextureData() != null) {
                    String str = "place " + place + ": bytesOut: " + bytesOut.length + " /expected: "
                        + imgSeries.getTextureData().getTotalSize();
                    LOGGER.debug(str);
                    if (bytesOut.length != imgSeries.getTextureData().getTotalSize()) {
                        sendError(ErrorCode.err501, imgSeries);
                    } else {
                        imgSeries.AddSliceData(place, bytesOut, addSliceListener);
                        return bufferClass;
                    }
                }
            }
        } else {
            imgSeries.textureLogInfo.writeText("Image not included! place = " + place);
        }
        return "No-data";
    }

    private static short[] getShortDataFromBuffer(final DataBuffer dataBuffer) {
        if (dataBuffer instanceof DataBufferShort) {
            return ((DataBufferShort) dataBuffer).getData();
        } else {
            return ((DataBufferUShort) dataBuffer).getData();
        }
    }

    private static int getAverage(ArrayList<Integer> listOfKeys) {
        int sunOfKeys = 0;
        for (int i = 0; i < listOfKeys.size(); i++) {
            sunOfKeys += listOfKeys.get(i);
        }
        return (Math.round(sunOfKeys / listOfKeys.size()));
    }

    private static int findHigherOccurrence(HashMap<Integer, Integer> map) {
        Set<Integer> keys = map.keySet();
        ArrayList<Integer> keysWithSameOccurrence = null;
        int max = 0;
        for (int key : keys) {
            int value = map.get(key);
            if (value >= max) {
                if (value == max) {
                    if (keysWithSameOccurrence != null) {
                        keysWithSameOccurrence.add(key);
                    }
                } else {
                    max = value;
                    keysWithSameOccurrence = new ArrayList<>();
                    keysWithSameOccurrence.add(key);
                }
            }
        }
        return getAverage(keysWithSameOccurrence);
    }

    private static double[] getHigherOccurrenceOfValues(ArrayList<double[]> listValues, int size) {
        double[] higherOccurrenceValues = new double[size];
        for (int i = 0; i < size; i++) {
            HashMap<Integer, Integer> mapOfOccurrence = new HashMap<>();
            for (double[] values : listValues) {
                int value = (int) values[i];
                if (mapOfOccurrence.containsKey(value)) {
                    int cont = mapOfOccurrence.get(value) + 1;
                    mapOfOccurrence.put(value, cont);
                } else {
                    int cont = 1;
                    mapOfOccurrence.put(value, cont);
                }
            }
            higherOccurrenceValues[i] = ((Integer) findHigherOccurrence(mapOfOccurrence)).floatValue();
        }
        return higherOccurrenceValues;
    }

    /**
     * Loads one image to a texture.
     *
     * @param element
     * @param imSeries
     * @param place
     */
    public static void loadOneImageToImageSeries(DicomImageElement element, TextureDicomSeries imSeries, int place) {
        new LoadOneImageThread(imSeries, element, place).execute();
    }

    private static String putElementInImageSeries(DicomImageElement dicomElement, TextureDicomSeries seriesToLoad,
        int place) {

        final PlanarImage image = dicomElement.getImage();

        // Modality LUT
        final LookupTableCV modalityLookup = dicomElement.getModalityLookup(null, true);

        boolean hasModalityLUT = false;
        PlanarImage imageMLUT;
        if (modalityLookup == null) {
            imageMLUT = image.toImageCV();
        } else {
            imageMLUT = modalityLookup.lookup(image.toMat());
            hasModalityLUT = true;
        }

        String dataBufferType = "";
        BufferedImage toBufferedImage = ImageConversion.toBufferedImage(ImageCV.toMat(imageMLUT));

        // ColorModel (for debug)
        ColorModel colorModel = toBufferedImage.getColorModel();

        dataBufferType = putBytesInImageSeries(place, toBufferedImage, seriesToLoad);

        //Log info:
        String colorTransformInfo = "Modality LUT: " + hasModalityLUT  + " / ColorModel: " + colorModel
                + " / DataBufferType: " + dataBufferType;
        return colorTransformInfo;

    }

    private static void updateOrientationTag(TextureDicomSeries seriesToLoad, DicomImageElement elmt) {
        double[] imOri = TagD.getTagValue(elmt, Tag.ImageOrientationPatient, double[].class);
        if (imOri != null && imOri.length == 6) {
            double[] serieOri = seriesToLoad.getOriginalSeriesOrientationPatient();
            if (serieOri == null) {
                seriesToLoad.getTextureGeometry().setOrientationPatient(imOri);
                StringBuilder bd = new StringBuilder();
                bd.append("First ImageOrientationPatient: ");
                for (double d : imOri) {
                    bd.append(d).append(", ");
                }
                LOGGER.info(bd.toString());
                seriesToLoad.textureLogInfo.writeText(bd.toString());
            } else {
                if (serieOri.length == 6
                    && !GeometryLoaderMath.isSameOrientation(imOri, seriesToLoad.getOriginalSeriesOrientationPatient())) {

                    // Could not find a case yet, but its possible!
                    seriesToLoad.textureLogInfo.writeText("Found variable ImageOrientationPatient.");
                    seriesToLoad.getTextureGeometry().setOrientationPatient(new double[] { 0 });
                }
            }
        }
    }

    private static void updateMultiplier(TextureDicomSeries seriesToLoad) {

        synchronized (seriesToLoad.getSeries()) {
            Iterator iterator = seriesToLoad.getSeries().getMedias(null, seriesToLoad.getSeriesSorter()).iterator();

            int place = 0;
            double lastPos = 0;
            double zSpacing = 1;

            while (iterator.hasNext()) {
                // dimensionMultiplier//////////////////////////////////////
                // take from SlicePosition (see SeriesBuilder::writeBlock)
                Object next = iterator.next();
                if (next instanceof DicomImageElement) {
                    DicomImageElement elmt = (DicomImageElement) next;

                    double[] sp = (double[]) elmt.getTagValue(TagW.SlicePosition);
                    if (sp != null) {
                        double pos = (sp[0] + sp[1] + sp[2]);
                        if (place > 0) {
                            double space = pos - lastPos;
                            TextureGeometry geometry = seriesToLoad.getTextureGeometry();
                            geometry.addZSpacingOccurence(space);
                            String log = geometry.submitAcquisitionPixelSpacing(place,
                                    GeometryLoaderMath.getPixelSpacingByTag(elmt));
                            LOGGER.info(log);

                            if (place == 1) { // is second
                                seriesToLoad.setDimensionMultiplier(geometry.getDimensionMultiplier());
                            } else if (place == seriesToLoad.getSeries().size(null) - 1) { // is last
                                zSpacing = seriesToLoad.getTextureGeometry().getMostCommonSpacing();
                                Vector3d vector = geometry.getDimensionMultiplier();
                                seriesToLoad.setDimensionMultiplier(vector);
                                seriesToLoad.textureLogInfo.writeText("Last dimension multiplier vector: " + vector);
                            }

                        }
                        lastPos = pos;
                    }
                }
                place++;
            }
        }
    }

    /**
     * Loads one image and updates some related parameters.
     *
     * @param seriesToLoad
     * @param element
     * @param place
     */
    private static void updateTextureElement(final TextureDicomSeries seriesToLoad, final DicomImageElement element,
        final int place) {

        // Must call getImage here to make ImageElement calculate MinMax values.
        if (!element.isImageAvailable()) {
            element.getImage();
        }

        double[] tagWidth = TagD.getTagValue(seriesToLoad, Tag.WindowWidth, double[].class);
        double[] tagCenter = TagD.getTagValue(seriesToLoad, Tag.WindowCenter, double[].class);
        ArrayList<double[]> listWidth = new ArrayList<>();
        ArrayList<double[]> listCenter = new ArrayList<>();
        if (tagWidth != null && tagCenter != null) {
            listWidth.add(tagWidth);
            listCenter.add(tagCenter);
        }
        feedInValues(element, seriesToLoad, listWidth, listCenter);

        String info = putElementInImageSeries(element, seriesToLoad, place);

        updateOrientationTag(seriesToLoad, element);
        LOGGER.debug("Series.size: {} / Loaded: {}: {}", seriesToLoad.getSeries().size(null), place, info);
    }

    private static class LoadOneImageThread extends SwingWorker<Void, Void> {
        private TextureDicomSeries seriesToLoad;
        private DicomImageElement element;
        private int place;

        protected LoadOneImageThread(TextureDicomSeries imSeries, DicomImageElement elmt, int place) {
            seriesToLoad = imSeries;
            element = elmt;
            this.place = place;
        }

        @Override
        protected Void doInBackground() throws Exception {
            updateTextureElement(seriesToLoad, element, place);

            if (seriesToLoad.getSeries().size(null) >= seriesToLoad.getSliceCount()) {
                updateMultiplier(seriesToLoad);
            }
            if (seriesToLoad.getSeries().size(null) == seriesToLoad.getSliceCount()) {
                fireProperyChange(this, TEXTURE_LOAD_COMPLETE, seriesToLoad.getSeries());
            }

            fireProperyChange(seriesToLoad, TEXTURE_DO_DISPLAY, seriesToLoad.getSeries());
            return null;
        }

        @Override
        public void done() {
            try {
                get();
            } catch (InterruptedException ex) {
                LOGGER.error("Interruption when loading series", ex);
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                LOGGER.error("Error when loading series", ex);
            }
        }
    }

    /**
     * Inner class Loader. Loads all imagens in order, using a secondary thread.
     */
    protected class LoaderThread extends SwingWorker<Void, Void> {
        protected TextureDicomSeries seriesToLoad;
        protected MediaSeries series;
        protected boolean pleaseCancel = false;

        protected LoaderThread(TextureDicomSeries seriesToLoad, MediaSeries series) {
            this.seriesToLoad = seriesToLoad;
            this.series = series;
        }

        protected void setToCancel() {
            LOGGER.debug("Factory of " + series + "marked to cancel.");
            pleaseCancel = true;
        }

        @Override
        protected Void doInBackground() throws Exception {
            ArrayList<double[]> windowValues = new ArrayList<>();
            ArrayList<double[]> levelValues = new ArrayList<>();

            String lastInfo = "";

            double lastPos = 0;
            boolean variableOP = false;

            seriesToLoad.windowingMinInValue = MAX_16;
            seriesToLoad.windowingMaxInValue = -MAX_16;

            // Se o comparator for Instance number dá para acrescentar as outras depois,
            // mas nao dá para usar Object media = series.getMedia(place, null, comparator);
            // enquanto wado nao acabou!

            int seriesSize = series.size(null);
            for (int place = 0; place < seriesSize; place++) {
                if (pleaseCancel) {
                    return null;
                }
                if (seriesSize > seriesToLoad.getSliceCount()) {
                    throw new IllegalAccessException("Has changed! ");
                }
                Object media = series.getMedia(place, null, comparator);
                if (media instanceof DicomImageElement) {
                    DicomImageElement elmt = (DicomImageElement) media;

                    // Must call getImage here to make ImageElement calculate MinMax values.
                    if (!elmt.isImageAvailable()) {
                        elmt.getImage();
                    }

                    feedInValues(elmt, seriesToLoad, windowValues, levelValues);

                    // dimensionMultiplier//////////////////////////////////////
                    // take from SlicePosition (see SeriesBuilder::writeBlock)
                    double[] sp = (double[]) elmt.getTagValue(TagW.SlicePosition);
                    if (sp != null) {

                        double pos = (sp[0] + sp[1] + sp[2]);
                        if (place > 0) {
                            double space = pos - lastPos;

                            TextureGeometry geometry = seriesToLoad.getTextureGeometry();

                            geometry.addZSpacingOccurence(space);
                            String log = geometry.submitAcquisitionPixelSpacing(place,
                                    GeometryLoaderMath.getPixelSpacingByTag(elmt));
                            LOGGER.info(log);

                            if (place == 1) { // is second
                                seriesToLoad.setDimensionMultiplier(geometry.getDimensionMultiplier());
                            } else if (place == seriesSize - 1) { // is last
                                Vector3d vector = geometry.getDimensionMultiplier();
                                seriesToLoad.setDimensionMultiplier(vector);
                                seriesToLoad.textureLogInfo.writeText("Last dimension multiplier vector: " + vector);
                            }
                        }
                        lastPos = pos;

                    }
                    // ///////////////////////////////////////////////////////

                    // ImageOrientationPatient //////////////////////////////
                    double[] imOri = TagD.getTagValue(elmt, Tag.ImageOrientationPatient, double[].class);
                    if (imOri != null && imOri.length == 6) {
                        if (place == 0) {
                            seriesToLoad.getTextureGeometry().setOrientationPatient(imOri);
                            StringBuilder bd = new StringBuilder();
                            bd.append("First ImageOrientationPatient: ");
                            for (double d : imOri) {
                                bd.append(d).append(", ");
                            }
                            LOGGER.info(bd.toString());
                            seriesToLoad.textureLogInfo.writeText(bd.toString());
                        } else {
                            if (!variableOP
                                && !GeometryLoaderMath.isSameOrientation(imOri,
                                        seriesToLoad.getOriginalSeriesOrientationPatient())) {

                                // Could not find a case yet, but its possible!
                                seriesToLoad.textureLogInfo.writeText("Found variable ImageOrientationPatient.");
                                variableOP = true;
                                seriesToLoad.getTextureGeometry().setOrientationPatient(new double[] { 0 });
                            }
                        }
                    }

                    String info = putElementInImageSeries(elmt, seriesToLoad, place);
                    if (!info.equals(lastInfo)) {
                        lastInfo = info;
                        LOGGER.info(lastInfo);
                        seriesToLoad.textureLogInfo.writeText(lastInfo);
                    }
                }

                if (place % 10 == 0) {
                    seriesToLoad.setDimensionMultiplier(seriesToLoad.getTextureGeometry().getDimensionMultiplier());
                    fireProperyChange(ImageSeriesFactory.this, TEXTURE_DO_DISPLAY, seriesToLoad.getSeries());
                }
            }

            seriesToLoad.textureLogInfo.writeText("Min in value: " + seriesToLoad.windowingMinInValue);
            final long stop = System.currentTimeMillis();
            String log = "Loading time: " + ((stop - timeStarted) / 1000) + " s";
            LOGGER.info(log);
            seriesToLoad.textureLogInfo.writeText(log);
            seriesToLoad.setFactoryDone(true);
            fireProperyChange(ImageSeriesFactory.this, TEXTURE_LOAD_COMPLETE, seriesToLoad.getSeries());

            return null;
        }

        @Override
        public void done() {
            try {
                get();
            } catch (InterruptedException ex) {
                seriesToLoad.setFactoryDone(true);
                seriesToLoad.setFactorySW(null);
                seriesToLoad.textureLogInfo.writeText("done by: " + ex);
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                seriesToLoad.setFactoryDone(true);
                seriesToLoad.setFactorySW(null);
                seriesToLoad.textureLogInfo.writeText("done with error: " + ex.getMessage());
                LOGGER.error("Error when loading series", ex);
            }
        }
    }

    protected class WadoSafeLoader extends LoaderThread {

        public WadoSafeLoader(TextureDicomSeries imSeries, MediaSeries series) {
            super(imSeries, series);
        }

        @Override
        protected Void doInBackground() throws Exception {
            Iterator iterator = series.copyOfMedias(null, SortSeriesStack.instanceNumber).iterator();

            boolean[] placesInVideo = seriesToLoad.getPlacesInVideo();

            while (iterator.hasNext()) {
                if (pleaseCancel) {
                    return null;
                }

                Object next = iterator.next();
                if (next instanceof DicomImageElement) {
                    DicomImageElement element = (DicomImageElement) next;
                    Integer inst = TagD.getTagValue(element, Tag.InstanceNumber, Integer.class);
                    if (inst == null) {
                        throw new IllegalArgumentException(
                            "Cant load images in multiple threads without" + " InstanceNumber information.");
                    }
                    if (!placesInVideo[inst - 1]) {
                        updateTextureElement(seriesToLoad, element, inst - 1);
                    }
                }
            }

            updateMultiplier(seriesToLoad);
            fireProperyChange(this, TEXTURE_LOAD_COMPLETE, seriesToLoad.getSeries());

            final long stop = System.currentTimeMillis();
            String log = "Loading time: " + ((stop - timeStarted) / 1000) + " s";
            LOGGER.info(log);
            seriesToLoad.textureLogInfo.writeText(log);

            seriesToLoad.setFactoryDone(true);
            return null;
        }
    }
}
