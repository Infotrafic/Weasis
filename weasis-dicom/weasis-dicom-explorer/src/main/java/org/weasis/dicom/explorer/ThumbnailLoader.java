package org.weasis.dicom.explorer;

import org.dcm4che3.data.Tag;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.wado.DownloadManager;
import org.weasis.dicom.explorer.wado.LoadSeries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ThumbnailLoader {
  private static final Comparator<LoadSeries> LOADING_SERIES_COMPARATOR =
          (a, b) -> {
            boolean aFirst = DicomSorter.isReformatted(a.getDicomSeries());
            boolean bFirst = DicomSorter.isReformatted(b.getDicomSeries());
            boolean aLast = DicomSorter.isDoseReport(a.getDicomSeries());
            boolean bLast = DicomSorter.isDoseReport(b.getDicomSeries());
            int defaultResult =
                    Integer.compare(a.getSeriesInstanceListSize(), b.getSeriesInstanceListSize());

            if (defaultResult == 0) {
              Integer aSeriesNb = TagD.getTagValue(a.getDicomSeries(), Tag.SeriesNumber, Integer.class);
              Integer bSeriesNb = TagD.getTagValue(b.getDicomSeries(), Tag.SeriesNumber, Integer.class);
              defaultResult = aSeriesNb.compareTo(bSeriesNb);
            }

            // At least one must be displayed first
            if (aFirst || bFirst) {
              // Compare size
              if (aFirst && bFirst) {
                return defaultResult;
              }

              return aFirst ? -1 : 1;
            }

            // At least one must be displayed at the end
            if (aLast || bLast) {
              // Compare size
              if (aLast && bLast) {
                return defaultResult;
              }

              return aLast ? 1 : -1;
            }

            return defaultResult;
          };

  private List<LoadSeries> loadSeries;
  private int currentLoading = 0;
  private boolean allPreloaded = false;
  private boolean[] isPreloaded;
  private boolean[] cannotLoad;
  private DicomModel model;

  public ThumbnailLoader(List<LoadSeries> loadSeries, DicomModel model) {
    this.loadSeries = loadSeries;
    this.model = model;

    // false by default
    this.isPreloaded = new boolean[loadSeries.size()];
    this.cannotLoad = new boolean[loadSeries.size()];
  }

  // Sort series in place by priority
  public static <T> void sortByPriority(List<T> collection) {
    Collections.sort(collection, DicomSorter.SERIES_COMPARATOR);
  }

  // Set current loading series priority
  public void setSeriesPriority() {
    // Copy the list to change its order
    List<LoadSeries> series = new ArrayList<>(DownloadManager.TASKS);

    // Load smallest series first
    Collections.sort(series, LOADING_SERIES_COMPARATOR);

    // Load remaining images in order
    for (int i = series.size() - 1; i >= 0; --i) {
      series.get(i).setForcePriority();
    }
  }

  // Preload the next thumbnail or the next series if every thumbnails preloaded
  public void preloadNext() {
    if (loadSeries.isEmpty() || allPreloaded) return;

    this.isPreloaded[currentLoading] = true;

    int oldCurrentLoading = currentLoading;

    // Start over if at the end
    currentLoading = (currentLoading + 1) % loadSeries.size();

    // Find next one not preloaded
    while (currentLoading != oldCurrentLoading && isPreloaded[currentLoading]) {
      currentLoading = (currentLoading + 1) % loadSeries.size();
    }

    // No more thumbnail to preload
    if (currentLoading == oldCurrentLoading) {
      // Verify that all thumbnails are loaded (check also that there is at least one image to load)
      for (int i = 0; i < loadSeries.size(); ++i) {
        LoadSeries series = loadSeries.get(i);

        // Not loaded, try again
        if (!cannotLoad[i] &&
                series.getSeriesInstanceListSize() != 0 &&
                series.getDicomSeries().size(null) == 0) {
          // Avoid infinite loop
          cannotLoad[i] = true;

          // Try to load it one more time
          currentLoading = i;
          loadSeries.get(currentLoading).setForcePriority();
          return;
        }
      }

      allPreloaded = true;
      currentLoading = 0;

      setSeriesPriority();
    } else {
      // Load the current series
      loadSeries.get(currentLoading).setForcePriority();
    }
  }

  // Whether all thumbnails are loaded
  public boolean isAllPreloaded() {
    return allPreloaded;
  }
}
