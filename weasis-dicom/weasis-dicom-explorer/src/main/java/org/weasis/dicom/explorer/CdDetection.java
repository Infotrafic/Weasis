package org.weasis.dicom.explorer;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.Timer;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.TimerTask;

public class CdDetection {
  // Duration between two cd detections in ms
  private static final long DETECTION_LOOP_PERIOD = 1000;

  private final DicomModel model;

  private boolean previouslyDetected = false;
  private Timer timer;

  public CdDetection(DicomModel model) {
    this.model = model;
    model.setCdDetection(this);
  }

  // Returns whether the CD is inserted but not necessarily mounted
  private static boolean isCdInserted() {
    try {
      ProcessBuilder pb = new ProcessBuilder("blkid", "/dev/sr0");
      Process process = pb.start();

      return process.waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  // Tests whether it is detected (inserted and mounted) now
  private static boolean isCdDetected() {
    if (!isCdInserted()) {
      return false;
    }

    try {
      // mount lists all mounted devices, /dev/sr0 is the CD device
      ProcessBuilder pb = new ProcessBuilder("mount");
      Process detector = pb.start();
      detector.waitFor();

      // Read mount's stdout
      InputStream stdout = detector.getInputStream();
      BufferedReader reader = new BufferedReader(new InputStreamReader(stdout));
      String line;
      while ((line = reader.readLine()) != null) {
        // CD found
        if (line.startsWith("/dev/sr0")) {
          return true;
        }
      }
      return false;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  public void start() {
    // Launch a timer which will call cdDetectionLoop periodically in a separate thread
    timer = new Timer();
    timer.scheduleAtFixedRate(
            new TimerTask() {
              @Override
              public void run() {
                cdDetectionLoop();
              }
            },
            0,
            DETECTION_LOOP_PERIOD);
  }

  public void stop() {
    timer.cancel();
  }

  private void cdDetectionLoop() {
    try {
      boolean mounted = isCdDetected();

      // The disk has been mounted since the last loop
      if (!previouslyDetected && mounted) {
        onCdDetected();
      }

      previouslyDetected = mounted;
    } catch (Exception e) {
      // Can't detect CDs
      previouslyDetected = false;
    }
  }

  // When the CD is mounted and was not mounted before
  private void onCdDetected() {
    new Thread(this::importCdContent).start();
  }

  // Load the DICOMDIR at the root of the auto mounted CD
  private void importCdContent() {
    // Try to import
    boolean importing = importCd();

    // Error, cannot import
    if (!importing) {
      SwingUtilities.invokeLater(
              () -> {
                // Ask to try again
                int tryAgainResult =
                        JOptionPane.showConfirmDialog(
                                null,
                                "Failed to import CD contents, retry ?",
                                "Import failed",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.ERROR_MESSAGE);

                if (tryAgainResult == JOptionPane.YES_OPTION) {
                  SwingUtilities.invokeLater(this::importCdContent);
                }
              });
    }
  }

  // Try to import the cd's contents
  // Returns whether the cd is imported
  private boolean importCd() {
    // Find where the cd is mounted
    File dicomDir = DicomDirImport.getDcmDirFromMedia();
    if (dicomDir == null) {
      return false;
    }

    // Launch import dialog (don't display it)
    try {
      SwingUtilities.invokeAndWait(
              () -> {
                DicomDirImport dialog = new DicomDirImport(dicomDir.getPath());
                dialog.setForceWriteInCache(true);
                dialog.importDICOM(model, null);
              });
    } catch (Exception e) {
      return false;
    }
    return true;
  }
}
