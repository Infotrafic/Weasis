/*
 * @copyright Copyright (c) 2012 Animati Sistemas de Informática Ltda.
 * (http://www.animati.com.br)
 */

package br.com.animati.texture.codec;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.SwingWorker;

import org.dcm4che3.data.Tag;
import org.weasis.core.api.image.LutShape;
import org.weasis.core.api.media.data.ImageElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.display.PresetWindowLevel;
import org.weasis.dicom.codec.geometry.GeometryOfSlice;

import br.com.animati.texturedicom.ImageSeries;
import br.com.animati.texturedicom.TextureData;

/**
 * Implements methods to get the ImageSeries of texturedicom more usable by weasis components.
 *
 * @author Rafaelo Pinheiro (rafaelo@animati.com.br)
 * @author Gabriela Bauermann (gabriela@animati.com.br)
 * @version 2013, 08, Aug.
 */
public class TextureDicomSeries<E extends ImageElement> extends ImageSeries implements MediaSeriesGroup {

    private final List<Object> oldIds = new ArrayList<>();
    private TagW tagID;
    private Map<TagW, Object> tags;
    /** Original series. */
    private MediaSeries<E> series;
    /** Information about the build process of the series`s texture. */
    public TextureLogInfo textureLogInfo;


    /** Window / Level presets list. */
    private List<PresetWindowLevel> windowingPresets;

    protected String pixelValueUnit = null;

    /** Series comparator used to build the texture. */
    private final Comparator<E> seriesComparator;

    private volatile boolean isFactoryDone = false;
    private ImageSeriesFactory.LoaderThread factoryReference;

    private boolean[] inVideo;

    private final TextureGeometry geometry = new TextureGeometry();

    /**
     * Builds an empty TextureImageSeries. Its best to use ImageSeriesFactory.
     *
     * @param sliceWidth
     * @param sliceHeight
     * @param sliceCount
     * @param format
     * @param series
     * @throws Exception
     */
    public TextureDicomSeries(final int sliceWidth, final int sliceHeight, final int sliceCount,
        final TextureData.Format format, MediaSeries series, Comparator sorter) throws Exception {
        super(sliceWidth, sliceHeight, sliceCount, format);

        this.series = series;
        textureLogInfo = new TextureLogInfo();

        tags = new HashMap<>();
        tagID = series.getTagID();
        tags.put(tagID, series.getTagValue(tagID));

        seriesComparator = sorter;

        inVideo = new boolean[sliceCount];
        Arrays.fill(inVideo, false);

        // DICOM $C.11.1.1.2 Modality LUT and Rescale Type
        // Specifies the units of the output of the Modality LUT or rescale operation.
        // Defined Terms:
        // OD = The number in the LUT represents thousands of optical density. That is, a value of
        // 2140 represents an optical density of 2.140.
        // HU = Hounsfield Units (CT)
        // US = Unspecified
        // Other values are permitted, but are not defined by the DICOM Standard.
        String modality = TagD.getTagValue(this, Tag.Modality, String.class);
        pixelValueUnit = TagD.getTagValue(this, Tag.RescaleType, String.class);
        if (pixelValueUnit == null) {
            // For some other modalities like PET
            pixelValueUnit = TagD.getTagValue(this, Tag.Units, String.class);
        }
        if (pixelValueUnit == null && "CT".equals(modality)) {
            pixelValueUnit = "HU";
        }
    }

    public boolean isFactoryDone() {
        return isFactoryDone;
    }

    protected void setFactoryDone(boolean done) {
        isFactoryDone = done;
    }

    public void setFactorySW(ImageSeriesFactory.LoaderThread thread) {
        factoryReference = thread;
    }

    public SwingWorker getFactorySW() {
        return factoryReference;
    }

    /**
     * @return The original series.
     */
    public MediaSeries getSeries() {
        return series;
    }

    @Override
    public TagW getTagID() {
        return tagID;
    }

    @Override
    public void setTag(TagW tag, Object value) {
        if (tag != null) {
            tags.put(tag, value);
        }
    }

    @Override
    public boolean containTagKey(TagW tag) {
        return tags.containsKey(tag);
    }

    @Override
    public Object getTagValue(TagW tag) {
        if (containTagKey(tag)) {
            return tags.get(tag);
        }
        return series.getTagValue(tag);
    }

    @Override
    public TagW getTagElement(int id) {
        Iterator<TagW> enumVal = tags.keySet().iterator();
        while (enumVal.hasNext()) {
            TagW e = enumVal.next();
            if (e.getId() == id) {
                return e;
            }
        }
        return null;
    }

    @Override
    public void dispose() {
        if (factoryReference != null) {
            factoryReference.setToCancel();
            factoryReference = null;
        }
    }


    @Override
    public void addMergeIdValue(Object valueID) {
        if(!oldIds.contains(valueID)) {
            oldIds.add(valueID);
        }
    }

    @Override
    public boolean matchIdValue(Object valueID) {
        Object v = tags.get(tagID);

        if(Objects.equals(v, valueID)) {
            return true;
        }
        for (Object id : oldIds) {
            if(Objects.equals(id, valueID)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        Object val = tags.get(tagID);
        result = prime * result + ((val == null) ? tags.hashCode() : val.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof MediaSeriesGroup)) {
            return false;
        }
        // According to the implementation of MediaSeriesGroupNode, the identifier cannot be null
        return Objects.equals(tags.get(tagID), ((MediaSeriesGroup) obj).getTagValue(tagID));
    }

    @Override
    public void setTagNoNull(TagW tag, Object value) {
        if (tag != null && value != null) {
            tags.put(tag, value);
        }
    }

    /**
     * @return True if the original series has known and regular slice-spacing.
     */
    public boolean isSliceSpacingRegular() {
        return geometry.isSliceSpacingRegular();
    }

    public boolean hasNegativeSliceSpacing() {
        return geometry.hasNegativeSliceSpacing();
    }

    /**
     * Returns a presset List.
     *
     * @param pixelPadding
     * @param force
     *            Set true to recalculate presetList.
     * @return preset list
     */
    public List<PresetWindowLevel> getPresetList(final boolean pixelPadding, final boolean force) {
        if (windowingPresets == null || force) {
            windowingPresets = buildPresetsList(pixelPadding);
        }
        return windowingPresets;
    }

    private List<PresetWindowLevel> buildPresetsList(boolean pixelPadding) {
        ArrayList<PresetWindowLevel> presetList = new ArrayList<>();

        double[] levelList = TagD.getTagValue(this, Tag.WindowCenter, double[].class);
        double[] windowList = TagD.getTagValue(this, Tag.WindowWidth, double[].class);
        // optional attributes
        String[] wlExplanationList = TagD.getTagValue(this, Tag.WindowCenterWidthExplanation, String[].class);
        // Implicitly defined as default function in DICOM standard
        // TODO: expect other cases.
        LutShape defaultLutShape = LutShape.LINEAR;

        // Adds Dicom presets
        if (levelList != null && windowList != null) {
            int wlDefaultCount = (levelList.length == windowList.length) ? levelList.length : 0;
            String defaultExp = "Default";

            int presCount = 1;
            for (int i = 0; i < wlDefaultCount; i++) {
                String name = defaultExp + " " + presCount;

                if (wlExplanationList != null && i < wlExplanationList.length) {
                    if (StringUtil.hasText(wlExplanationList[i])) {
                        name = wlExplanationList[i]; // optional attribute
                    }
                } else {
                    PresetWindowLevel preset =
                        new PresetWindowLevel(name + " [Dicom]", windowList[i], levelList[i], defaultLutShape);
                    if (presCount == 1) {
                        preset.setKeyCode(KeyEvent.VK_1);
                    } else if (presCount == 2) {
                        preset.setKeyCode(KeyEvent.VK_2);
                    }
                    if (!presetList.contains(preset)) {
                        presetList.add(preset);
                        presCount++;
                    }
                }
            }
        }

        // TODO VoiLut !!

        // AutoLevel
        PresetWindowLevel autoLevel =
            new PresetWindowLevel(org.weasis.dicom.codec.Messages.getString("PresetWindowLevel.full"),
                getFullDynamicWidth(pixelPadding), getFullDynamicCenter(pixelPadding), defaultLutShape);
        // Set O shortcut for auto levels
        autoLevel.setKeyCode(KeyEvent.VK_0);
        presetList.add(autoLevel);

        // Arbitrary Presets by Modality
        // TODO: need to exclude 8-bits images from here.
        List<PresetWindowLevel> modPresets =
            StaticHelpers.getPresetListByModality().get(TagD.getTagValue(this, Tag.Modality));
        if (modPresets != null) {
            presetList.addAll(modPresets);
        }

        return presetList;
    }

    public double getFullDynamicWidth(boolean pixelPadding) {
        // TODO: needs to change if we use pixelPadding optional.
        return windowingMaxInValue - windowingMinInValue;
    }

    public double getFullDynamicCenter(boolean pixelPadding) {
        // TODO: needs to change if we use pixelPadding optional.
        return windowingMinInValue + (windowingMaxInValue - windowingMinInValue) / 2.0;
    }

    /**
     * Correct the level value by rescale-slope to fit the video-card range.
     *
     * Still not tested for SignedShort with RescaleSlope not 1.
     *
     * @param window
     *            Window value as shown to the user.
     * @return the corrected window to be applied to this series.
     */
    public int getCorrectedValueForWindow(int window) {
        Double slopeVal = TagD.getTagValue(this, Tag.RescaleSlope, Double.class);
        final double slope = slopeVal == null ? 1.0 : slopeVal;

        return (int) Math.round(window / slope);
    }

    /**
     * Valid if has 6 double s. Set to a double[] of one element to make not-valid.
     *
     * @return
     */
    public double[] getOriginalSeriesOrientationPatient() {
        return geometry.getOriginalSeriesOrientationPatient();
    }

    public Comparator<E> getSeriesSorter() {
        return seriesComparator;
    }

    public TextureGeometry getTextureGeometry() {
        return geometry;
    }

    public double[] getAcquisitionPixelSpacing() {
        return geometry.getAcquisitionPixelSpacing();
    }

    public PixelSize getShowingPixelSize(boolean acquisition, int viewingSlice) {
        return geometry.getShowingPixelSize(acquisition, viewingSlice);
    }

    public boolean isVolumeScaleTrustable() {
        return geometry.isVolumeScaleTrustable();
    }


    /**
     * Gets a tagValue from the original DicomImageElement on this location.
     *
     * @param tag
     *            Tag object
     * @param currentSlice
     *            Slices: 0 to N-1.
     * @return Tag value, if it exists.
     */
    public Object getTagValue(TagW tag, int currentSlice) {
        if (getSliceCount() == series.size(null)) {
            Object media = getSeries().getMedia(currentSlice, null, seriesComparator);
            if (media instanceof DicomImageElement) {
                return ((DicomImageElement) media).getTagValue(tag);
            }
        }
        return null;
    }

    public boolean isPhotometricInterpretationInverse(int currentSlice) {
        Object media = getSeries().getMedia(currentSlice, null, seriesComparator);
        if (media instanceof DicomImageElement) {
            return ((DicomImageElement) media).isPhotometricInterpretationInverse(null);
        }
        return false;
    }

    public String getPixelValueUnit() {
        return pixelValueUnit;
    }

    public void setInVideo(int sliceIndex, boolean isComplete) {
        if (sliceIndex < inVideo.length && sliceIndex >= 0) {
            inVideo[sliceIndex] = isComplete;
        }
    }

    public boolean isAllInVideo() {
        for (boolean loaded : inVideo) {
            if (!loaded) {
                return false;
            }
        }
        return true;
    }

    public boolean[] getPlacesInVideo() {
        return inVideo.clone();
    }

    public boolean isDownloadDone() {
        if (getSliceCount() > series.size(null)) {
            return false;
        }
        return true;
    }

    // ////////////////////////////////////////////////////////////

    private MonitorThread monitor;
    private int seriesSize = 0;
    protected volatile boolean isToCountObjects = false;

    public void countObjects() {
        if (getSeries() != null && isToCountObjects) {
            if (monitor == null) {
                monitor = new MonitorThread();
                seriesSize = getSliceCount();
                // Never use start() (it will start 2 threads).
                monitor.restart();
            } else {
                if (getSeries().size(null) != seriesSize) {
                    monitor.restart();
                    seriesSize = getSeries().size(null);
                }
            }
        }
    }

    /**
     * Get the GeometryOfSlice of on slice from the original series.
     *
     * @param currentSlice
     *            Slice to get the geometry from.
     * @return Geometry of given slice.
     */
    public GeometryOfSlice getSliceGeometry(int currentSlice) {
        Object media = getSeries().getMedia(currentSlice - 1, null, seriesComparator);
        if (media instanceof DicomImageElement) {
            return ((DicomImageElement) media).getDispSliceGeometry();
        }
        return null;
    }

    public int getNearestSliceIndex(final Double location) {
        Iterable<E> mediaList = series.getMedias(null, seriesComparator);
        int index = 0;
        int bestIndex = -1;
        synchronized (this) {
            double bestDiff = Double.MAX_VALUE;
            for (Iterator<E> iter = mediaList.iterator(); iter.hasNext();) {
                E dcm = iter.next();
                double[] val = (double[]) dcm.getTagValue(TagW.SlicePosition);
                if (val != null) {
                    double diff = Math.abs(location - (val[0] + val[1] + val[2]));
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestIndex = index;
                        if (diff == 0.0) {
                            break;
                        }
                    }
                }
                index++;
            }
        }
        return bestIndex;
    }

    public void interruptFactory() {
        if (factoryReference != null) {
            factoryReference.setToCancel();
            factoryReference = null;
        }
        if (monitor != null) {
            monitor.interrupt();
            if (monitor.internalThread != null) {
                monitor.internalThread.interrupt();
                monitor.internalThread = null;
            }
            monitor = null;
        }
        ImageSeriesFactory.removeFromCache(this);
    }

    @Override
    public Iterator<Map.Entry<TagW, Object>> getTagEntrySetIterator() {
        return tags.entrySet().iterator();
    }

    protected class MonitorThread extends Thread {

        @Override
        public void run() {
            try {
                Thread.sleep(5000);
                ImageSeriesFactory.fireProperyChange(TextureDicomSeries.this, "RefreshTexture",
                    TextureDicomSeries.this);
                internalThread = null;
            } catch (InterruptedException e) {
            }
        }

        public Thread internalThread;

        public void restart() {
            if (internalThread != null) {
                internalThread.interrupt();
            }
            internalThread = new MonitorThread();
            internalThread.start();
        }
    }
}
