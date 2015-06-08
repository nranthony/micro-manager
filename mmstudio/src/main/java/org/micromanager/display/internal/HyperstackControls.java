///////////////////////////////////////////////////////////////////////////////
//PROJECT:       Micro-Manager
//SUBSYSTEM:     Display implementation
//-----------------------------------------------------------------------------
//
// AUTHOR:       Chris Weisiger, 2015
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

/*
 * HyperstackControls.java
 *
 * Created on Jul 15, 2010, 2:54:37 PM
 */
package org.micromanager.display.internal;

import com.google.common.eventbus.Subscribe;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.concurrent.TimeUnit;

import javax.swing.JLabel;
import javax.swing.JPanel;

import net.miginfocom.swing.MigLayout;


import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.display.DisplayWindow;

import org.micromanager.data.internal.NewImageEvent;
import org.micromanager.display.internal.events.FPSEvent;
import org.micromanager.display.internal.events.MouseMovedEvent;
import org.micromanager.display.internal.events.StatusEvent;
import org.micromanager.internal.utils.ReportingUtils;


public class HyperstackControls extends JPanel {

   private final DisplayWindow parent_;
   private final Datastore store_;
   private final MMVirtualStack stack_;

   // Last known mouse positions.
   private int mouseX_ = 0;
   private int mouseY_ = 0;

   // Controls common to both control sets
   private ScrollerPanel scrollerPanel_;
   private JLabel pixelInfoLabel_;
   private JLabel fpsLabel_;
   // Displays the countdown to the next frame.
   private JLabel countdownLabel_;
   // Displays general status information.
   private JLabel statusLabel_;

   /**
    * @param store
    * @param stack
    * @param parent
    * @param shouldUseLiveControls - indicates if we should use the buttons for 
    *        the "Snap/Live" window or the buttons for normal displays.
    */
   public HyperstackControls(Datastore store, MMVirtualStack stack,
         DisplayWindow parent, boolean shouldUseLiveControls) {
      super(new FlowLayout(FlowLayout.LEADING));
      parent_ = parent;
      store_ = store;
      store_.registerForEvents(this);
      stack_ = stack;
      initComponents();
      parent_.registerForEvents(this);
   }

   private void initComponents() {
      // This layout minimizes space between components.
      setLayout(new MigLayout("insets 0, fillx, align center"));

      java.awt.Font labelFont = new java.awt.Font("Lucida Grande", 0, 10);
      // HACK: we allocate excessive height for our text fields on purpose;
      // if we try to make them precise to the text that will be displayed,
      // then we risk them growing and taking space that should be used for
      // the canvas.
      Dimension labelDimension = new Dimension(10, 13);
      JPanel labelsPanel = new JPanel(new MigLayout("insets 0"));
      pixelInfoLabel_ = new JLabel();
      pixelInfoLabel_.setMinimumSize(labelDimension);
      pixelInfoLabel_.setFont(labelFont);
      labelsPanel.add(pixelInfoLabel_, "grow");

      fpsLabel_ = new JLabel();
      fpsLabel_.setMinimumSize(labelDimension);
      fpsLabel_.setFont(labelFont);
      labelsPanel.add(fpsLabel_, "grow");
      
      countdownLabel_ = new JLabel();
      countdownLabel_.setMinimumSize(labelDimension);
      countdownLabel_.setFont(labelFont);
      labelsPanel.add(countdownLabel_, "grow");

      statusLabel_ = new JLabel();
      statusLabel_.setMinimumSize(labelDimension);
      statusLabel_.setFont(labelFont);
      labelsPanel.add(statusLabel_, "grow");

      add(labelsPanel, "span, growx, align center, wrap");

      scrollerPanel_ = new ScrollerPanel(store_, parent_);
      add(scrollerPanel_, "span, growx, shrinkx, wrap 0px");
   }

   /**
    * User moused over the display; update our indication of pixel intensities. 
    * @param event
    * */
   @Subscribe
   public void onMouseMoved(MouseMovedEvent event) {
      try {
         mouseX_ = event.getX();
         mouseY_ = event.getY();
         setPixelInfo(mouseX_, mouseY_);
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Failed to get image pixel info");
      }
   }

   public String getIntensityString(int x, int y) {
      int numChannels = store_.getAxisLength("channel");
      if (numChannels > 1) {
         // Multi-channel case: display each channel with a "/" in-between.
         String intensity = "[";
         for (int i = 0; i < numChannels; ++i) {
            Coords imageCoords = stack_.getCurrentImageCoords().copy().channel(i).build();
            Image image = store_.getImage(imageCoords);
            // It can be null if not all channels for this imaging event have
            // arrived yet.
            if (image != null) {
               intensity += image.getIntensityStringAt(x, y);
            }
            if (i != numChannels - 1) {
               intensity += "/";
            }
         }
         intensity += "]";
         return intensity;
      }
      else {
         // Single-channel case; simple.
         Image image = store_.getImage(stack_.getCurrentImageCoords());
         if (image != null) {
            try {
               return image.getIntensityStringAt(x, y);
            }
            catch (IllegalArgumentException e) {
               // Our x/y values were out-of-bounds; this should never happen.
               ReportingUtils.logError("Invalid pixel coordinates " + x + ", " + y);
            }
         }
      }
      return "";
   }

   /**
    * Update our pixel info text.
    */
   private void setPixelInfo(int x, int y) {
      String intensity = getIntensityString(x, y);
      pixelInfoLabel_.setText(String.format("x=%d, y=%d, value=%s",
               x, y, intensity));
      // If the pixel info display grows (e.g. due to extra digits in the
      // intensity display) then we don't want to let it shrink again, or else
      // the FPS display to its right will get bounced back and forth.
      pixelInfoLabel_.setMinimumSize(pixelInfoLabel_.getSize());
      // This validate call reduces the chance that the text will be truncated.
      validate();
   }

   /**
    * A new image has been made available. Update our pixel info, assuming
    * we have a valid mouse position.
    * @param event
    */
   @Subscribe
   public void onNewImage(NewImageEvent event) {
      Image image = event.getImage();
      if (mouseX_ >= 0 && mouseX_ < image.getWidth() &&
            mouseY_ >= 0 && mouseY_ < image.getHeight()) {
         try {
            setPixelInfo(mouseX_, mouseY_);
         }
         catch (Exception e) {
            ReportingUtils.logError(e, "Error in HyperstackControls onNewImage");
         }
      }
   }

   /**
    * New information on our FPS; update a label.
    * @param event
    */
   @Subscribe
   public void onFPSUpdate(FPSEvent event) {
      // Default to assuming we'll be blanking the label.
      String newLabel = "";
      if (event.getDataFPS() != 0) {
         newLabel = String.format("FPS: %.1f (display %.1f)",
               event.getDataFPS(), event.getDisplayFPS());
      }
      else if (event.getDisplayFPS() != 0) {
         newLabel = String.format("Display FPS: %.1f", event.getDisplayFPS());
      }
      fpsLabel_.setText(newLabel);
      validate();
   }

   @Subscribe
   public void onStatus(StatusEvent event) {
      statusLabel_.setText(event.getStatus());
   }

   public static String elapsedTimeDisplayString(double seconds) {
      // Use "12.34s" up to 60 s; "12m 34.56s" up to 1 h, and
      // "1h 23m 45s" beyond that.

      long wholeSeconds = (long) Math.floor(seconds);
      double fraction = seconds - wholeSeconds;

      long hours = TimeUnit.SECONDS.toHours(wholeSeconds);
      wholeSeconds -= TimeUnit.HOURS.toSeconds(hours);
      String hoursString = "";
      if (hours > 0) {
         hoursString = hours + "h ";
      }

      long minutes = TimeUnit.SECONDS.toMinutes(wholeSeconds);
      wholeSeconds -= TimeUnit.MINUTES.toSeconds(minutes);
      String minutesString = "";
      if (minutes > 0) {
         minutesString = minutes + "m ";
      }

      String secondsString;
      if (hours == 0 && fraction > 0.01) {
         secondsString = String.format("%.2fs", wholeSeconds + fraction);
      }
      else {
         secondsString = wholeSeconds + "s";
      }

      return hoursString + minutesString + secondsString;
   }

   @Subscribe
   public void onDisplayDestroyed(DisplayDestroyedEvent event) {
      parent_.unregisterForEvents(this);
      store_.unregisterForEvents(this);
   }
}