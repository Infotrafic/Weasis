/*******************************************************************************
 * Copyright (c) 2016 Weasis Team and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 *******************************************************************************/
package org.weasis.acquire.graphics;

import java.awt.Color;
import java.util.Collections;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JOptionPane;

import org.weasis.acquire.AcquireObject;
import org.weasis.acquire.dockable.EditionToolFactory;
import org.weasis.acquire.dockable.components.actions.calibrate.CalibrationPanel;
import org.weasis.acquire.explorer.AcquireImageInfo;
import org.weasis.acquire.explorer.AcquireManager;
import org.weasis.core.api.gui.util.ComboItemListener;
import org.weasis.core.api.image.util.MeasurableLayer;
import org.weasis.core.api.image.util.Unit;
import org.weasis.core.api.media.data.ImageElement;
import org.weasis.core.ui.editor.image.CalibrationView;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.model.GraphicModel;
import org.weasis.core.ui.model.graphic.imp.line.LineGraphic;
import org.weasis.core.ui.model.layer.LayerType;
import org.weasis.core.ui.model.utils.bean.MeasureItem;
import org.weasis.core.ui.model.utils.bean.Measurement;
import org.weasis.core.ui.util.MouseEventDouble;

public class CalibrationGraphic extends LineGraphic {
    private static final long serialVersionUID = -6996238746877983645L;

    public CalibrationGraphic() {
        super();
        setColorPaint(Color.RED);
    }

    public CalibrationGraphic(CalibrationGraphic calibrationGraphic) {
        super(calibrationGraphic);
    }

    @Override
    public void buildShape(MouseEventDouble mouseevent) {
        super.buildShape(mouseevent);
        ViewCanvas<ImageElement> view = AcquireObject.getView();
        GraphicModel graphicManager = view.getGraphicManager();
        if (graphicManager.getModels().removeIf(g -> g.getLayer().getType() == getLayerType() && g != this)) {
            graphicManager.fireChanged();
        }

        if (!getResizingOrMoving()) {
            CalibrationView calibrationDialog = new CalibrationView(this, view, false);
            int res = JOptionPane.showConfirmDialog(view.getJComponent(), calibrationDialog, "Calibration",
                JOptionPane.OK_CANCEL_OPTION);
            if (res == JOptionPane.OK_OPTION) {
                calibrationDialog.applyNewCalibration();
                if (calibrationDialog.isApplyingToSeries()) {
                    ImageElement image = view.getImage();
                    if (image != null) {
                        AcquireImageInfo info = AcquireManager.findByImage(image);
                        if (info != null) {
                            List<AcquireImageInfo> list = AcquireManager.findbySerie(info.getSerie());
                            for (AcquireImageInfo acquireImageInfo : list) {
                                ImageElement img = acquireImageInfo.getImage();
                                if (img != image) {
                                    img.setPixelSpacingUnit(image.getPixelSpacingUnit());
                                    img.setPixelSize(image.getPixelSize());
                                }
                            }
                        }
                    }
                }
            }

            view.getEventManager().getAction(EditionToolFactory.DRAW_EDITON, ComboItemListener.class)
                .ifPresent(a -> a.setSelectedItem(CalibrationPanel.CALIBRATION_LINE_GRAPHIC));
        }
    }

    @Override
    public Icon getIcon() {
        return LineGraphic.ICON;
    }

    @Override
    public LayerType getLayerType() {
        return LayerType.ACQUIRE;
    }

    @Override
    public String getUIName() {
        return "Calibration line";
    }

    @Override
    public List<MeasureItem> computeMeasurements(MeasurableLayer layer, boolean releaseEvent, Unit displayUnit) {
        return Collections.emptyList();
    }

    @Override
    public List<Measurement> getMeasurementList() {
        return Collections.emptyList();
    }

    @Override
    public CalibrationGraphic copy() {
        return new CalibrationGraphic(this);
    }
}
