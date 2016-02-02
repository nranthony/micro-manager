///////////////////////////////////////////////////////////////////////////////
//PROJECT:       Micro-Manager
//SUBSYSTEM:     Data API implementation
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

package org.micromanager.data.internal;

import ij.ImagePlus;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import mmcorej.TaggedImage;

import org.json.JSONException;
import org.json.JSONObject;

import org.micromanager.PropertyMap;
import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.DatastoreFrozenException;
import org.micromanager.data.Image;
import org.micromanager.data.DatastoreRewriteException;
import org.micromanager.data.internal.DefaultPropertyMap;
import org.micromanager.data.Metadata;

import org.micromanager.internal.MMStudio;
import org.micromanager.internal.utils.DirectBuffers;
import org.micromanager.internal.utils.ImageUtils;
import org.micromanager.internal.utils.MDUtils;
import org.micromanager.internal.utils.MMScriptException;
import org.micromanager.internal.utils.ReportingUtils;

/**
 * This class represents a single image from a single camera. It contains
 * the image pixel data, metadata (in the form of a Metadata instance), and
 * the image's index as part of a larger dataset (in the form of an
 * Coords instance).
 *
 * For efficiency during high-speed acquisitions, we store the image data in
 * a ByteBuffer or ShortBuffer. However, ImageJ wants to work with image data
 * in the form of byte[], short[], or int[] arrays (depending on pixel type).
 * getRawPixels(), the method exposed in the Image interface to access pixel
 * data, returns an ImageJ-style array, while getPixelBuffer (which is not
 * exposed in the API) returns the raw buffer.
 * TODO: add method to generate an ImagePlus from the image.
 */
public class DefaultImage implements Image {
   private static final String COORDS_TAG = "completeCoords";

   private DefaultMetadata metadata_;
   private Coords coords_;
   private Buffer rawPixels_;
   // Width of the image, in pixels
   int pixelWidth_;
   // Height of the image, in pixels
   int pixelHeight_;
   // How many bytes are allocated to each pixel in rawPixels_. This is
   // different from the bits per pixel, which is in the Metadata, and
   // indicates the range of values that the camera can output (e.g. a 12-bit
   // pixel has values in [0, 4095].
   int bytesPerPixel_;
   // How many bytes are allocated to a single component's intensity in a
   // given pixel. For example, 1 for single-component 8-bit, 2 for
   // single-component 16-bit, 1 for RGB-32bit.
   int bytesPerComponent_;
   // How many components are packed into each pixel's worth of data (e.g. an
   // RGB or CMYK image).
   int numComponents_;

   /**
    * Generate a DefaultImage from a TaggedImage. Note that this method will
    * result in Micro-Manager assuming that the image data was generated by
    * the scope that it is currently running (i.e. this is a bad method to use
    * for loading saved images). If you want to avoid that, then you need to
    * manually reconstruct the Metadata for the TaggedImage and use the
    * constructor that this method calls.
    * @param tagged A TaggedImage to base the Image on.
    */
   public DefaultImage(TaggedImage tagged) throws JSONException, MMScriptException {
      this(tagged, null, null);
   }

   /**
    * As above but allows either or both of the image coords and metadata to be
    * overridden.
    */
   public DefaultImage(TaggedImage tagged, Coords coords, Metadata metadata)
         throws JSONException, MMScriptException {
      JSONObject tags = tagged.tags;
      if (metadata == null) {
         metadata = DefaultMetadata.legacyFromJSON(tags);
         // HACK: assume that this Image was generated by the current hardware,
         // and attempt to recover the scopeData and Camera properties (which
         // depends on the current device adapters).
         // HACK: assume all remaining properties are user data.
         // TODO: this means that the data layer depends on MMStudio.
         DefaultPropertyMap scopeData = (DefaultPropertyMap) MDUtils.extractScopeData(tags);
         PropertyMap userData = MDUtils.extractUserData(tags,
               scopeData.getKeys());
         String camera = metadata.getCamera();
         if (camera == null) {
            camera = MMStudio.getInstance().getCore().getCameraDevice();
         }
         metadata = metadata.copy().scopeData(scopeData).userData(userData).camera(camera).build();
      }
      metadata_ = (DefaultMetadata) metadata;

      if (coords == null) {
         DefaultCoords.Builder cBuilder = new DefaultCoords.Builder();
         try {
            cBuilder.time(MDUtils.getFrameIndex(tags));
         }
         catch (JSONException e) {}
         try {
            cBuilder.stagePosition(MDUtils.getPositionIndex(tags));
         }
         catch (JSONException e) {}
         try {
            cBuilder.z(MDUtils.getSliceIndex(tags));
         }
         catch (JSONException e) {}
         try {
            cBuilder.channel(MDUtils.getChannelIndex(tags));
         }
         catch (JSONException e) {}
         coords = cBuilder.build();
      }
      coords_ = coords;

      rawPixels_ = DirectBuffers.bufferFromArray(tagged.pix);
      if (rawPixels_.capacity() == 0) {
         throw new IllegalArgumentException("Pixel data has length 0.");
      }
      pixelWidth_ = MDUtils.getWidth(tags);
      pixelHeight_ = MDUtils.getHeight(tags);
      bytesPerPixel_ = MDUtils.getBytesPerPixel(tags);
      setBytesPerComponent();
      numComponents_ = MDUtils.getNumberOfComponents(tags);
   }

   /**
    * @param pixels Assumed to be a Java array of either bytes or shorts.
    */
   public DefaultImage(Object pixels, int width, int height, int bytesPerPixel,
         int numComponents, Coords coords, Metadata metadata) 
         throws IllegalArgumentException {
      metadata_ = (DefaultMetadata) metadata;
      if (metadata_ == null) {
         // Don't allow images with null metadata.
         metadata_ = new DefaultMetadata.Builder().build();
      }
      coords_ = coords;

      rawPixels_ = DirectBuffers.bufferFromArray(pixels);
      if (rawPixels_ == null || rawPixels_.capacity() == 0) {
         throw new IllegalArgumentException("Invalid pixel data " + pixels);
      }
      pixelWidth_ = width;
      pixelHeight_ = height;
      bytesPerPixel_ = bytesPerPixel;
      setBytesPerComponent();
      numComponents_ = numComponents;
   }

   public DefaultImage(Image source, Coords coords, Metadata metadata) {
      metadata_ = (DefaultMetadata) metadata;
      coords_ = coords;
      if (source instanceof DefaultImage) {
         // Just copy their Buffer over directly.
         rawPixels_ = ((DefaultImage) source).getPixelBuffer();
      }
      else {
         rawPixels_ = DirectBuffers.bufferFromArray(source.getRawPixels());
      }
      if (rawPixels_.capacity() == 0) {
         throw new IllegalArgumentException("Pixel data has length 0.");
      }
      pixelWidth_ = source.getWidth();
      pixelHeight_ = source.getHeight();
      bytesPerPixel_ = source.getBytesPerPixel();
      setBytesPerComponent();
      numComponents_ = source.getNumComponents();
   }

   private void setBytesPerComponent() {
      if (rawPixels_ instanceof ByteBuffer) {
         bytesPerComponent_ = 1;
      }
      else if (rawPixels_ instanceof ShortBuffer) {
         bytesPerComponent_ = 2;
      }
      else {
         throw new IllegalArgumentException("Unsupported number of bytes per component");
      }
   }

   @Override
   public Metadata getMetadata() {
      return metadata_;
   }

   @Override
   public Coords getCoords() {
      return coords_;
   }

   @Override
   public Image copyAtCoords(Coords coords) {
      return new DefaultImage(this, coords, metadata_);
   }

   @Override
   public Image copyWithMetadata(Metadata metadata) {
      return new DefaultImage(this, coords_, metadata);
   }

   @Override
   public Image copyWith(Coords coords, Metadata metadata) {
      return new DefaultImage(this, coords, metadata);
   }

   /**
    * Note this returns a byte[], short[], or int[] array, not a ByteBuffer,
    * ShortBuffer, or IntBuffer. Use getPixelBuffer() for that.
    */
   @Override
   public Object getRawPixels() {
      return DirectBuffers.arrayFromBuffer(rawPixels_);
   }

   @Override
   public Object getRawPixelsCopy() {
      Object original = getRawPixels();
      Object copy;
      int length;
      if (original instanceof byte[]) {
         byte[] tmp = (byte[]) original;
         length = tmp.length;
         copy = new byte[length];
      }
      else if (original instanceof short[]) {
         short[] tmp = (short[]) original;
         length = tmp.length;
         copy = new short[length];
      }
      else if (original instanceof int[]) {
         int[] tmp = (int[]) original;
         length = tmp.length;
         copy = new int[length];
      }
      else {
         throw new RuntimeException("Unrecognized pixel type " + original.getClass());
      }
      System.arraycopy(original, 0, copy, 0, length);
      return copy;
   }

   public Buffer getPixelBuffer() {
      return rawPixels_;
   }

   // This is a bit ugly, due to needing to examine the type of rawPixels_,
   // but what else can we do?
   @Override
   public Object getRawPixelsForComponent(int component) {
      // The divisor to use here is a little squicky; for multi-component
      // images it should be the bytes per pixel (not the number of components)
      // as ARGB images have 3 components but 4 bytes per pixel. But for
      // single-component images of course we want a divisor of only 1.
      int divisor = (numComponents_ == 1) ? 1 : bytesPerPixel_;
      int length = rawPixels_.capacity() / divisor;
      Object result;
      if (rawPixels_ instanceof ByteBuffer) {
         result = (Object) new byte[length];
      }
      else if (rawPixels_ instanceof ShortBuffer) {
         result = (Object) new short[length];
      }
      else {
         ReportingUtils.logError("Unrecognized pixel buffer type.");
         return null;
      }
      for (int i = 0; i < length; ++i) {
         // See above re: divisor for why we have to do fiddly stuff here.
         int sourceIndex = i * (numComponents_ == 1 ? 1 : bytesPerPixel_) + component;
         if (rawPixels_ instanceof ByteBuffer) {
            ((byte[]) result)[i] = ((ByteBuffer) rawPixels_).get(sourceIndex);
         }
         else if (rawPixels_ instanceof ShortBuffer) {
            ((short[]) result)[i] = ((ShortBuffer) rawPixels_).get(sourceIndex);
         }
      }
      return result;
   }

   @Override
   public long getIntensityAt(int x, int y) {
      return getComponentIntensityAt(x, y, 0);
   }

   @Override
   public long getComponentIntensityAt(int x, int y, int component) {
      int pixelIndex = (y * pixelWidth_ + x) * bytesPerPixel_ / bytesPerComponent_ + component;
      if (pixelIndex < 0 || pixelIndex >= rawPixels_.capacity()) {
         throw new IllegalArgumentException(
               String.format("Asked for pixel at (%d, %d) component %d outside of pixel array size of %d (calculated index %d)",
                  x, y, component, rawPixels_.capacity(), pixelIndex));
      }
      long result = 0;
      int exponent = 8;
      // "Value" meaning "entry in our buffer".
      int numValues = bytesPerComponent_;
      if (rawPixels_ instanceof ShortBuffer) {
         exponent = 16;
         numValues /= 2;
      }
      for (int i = 0; i < numValues; ++i) {
         // NB Java will let you use "<<=" in this situation.
         result = result << exponent;
         int index = (y * pixelWidth_ + x) * bytesPerPixel_ / bytesPerComponent_ + component * bytesPerComponent_ + i;
         // Java doesn't have unsigned number types, so we have to manually
         // convert; otherwise large numbers will set the sign bit and show
         // as negative.
         int addend = 0;
         if (rawPixels_ instanceof ByteBuffer) {
            addend = ImageUtils.unsignedValue(
                  ((ByteBuffer) rawPixels_).get(index));
         }
         else if (rawPixels_ instanceof ShortBuffer) {
            addend = ImageUtils.unsignedValue(
                  ((ShortBuffer) rawPixels_).get(index));
         }
         result += addend;
      }
      return result;
   }

   @Override
   public String getIntensityStringAt(int x, int y) {
      if (numComponents_ == 1) {
         return String.format("%d", getIntensityAt(x, y));
      }
      else {
         String result = "[";
         for (int i = 0; i < numComponents_; ++i) {
            result += String.format("%d", getComponentIntensityAt(x, y, i));
            if (i != numComponents_ - 1) {
               result += "/";
            }
         }
         return result + "]";
      }
   }

   /**
    * Split this multi-component Image into several single-component Images.
    * They will be positioned based on our current index, with the channel
    * incrementing by 1 for each new component.
    * TODO: this will work horribly if there are any cameras located "after"
    * this camera along the channel axis, since it blindly inserts new images
    * at C, C+1...C+N where C is its channel index and N is the number of
    * components.
    */
   public List<Image> splitMultiComponent() {
      ArrayList<Image> result = new ArrayList<Image>();
      if (numComponents_ == 1) {
         // Already only one component; just return us.
         result.add(this);
         return result;
      }
      for (int i = 0; i < numComponents_; ++i) {
         Object pixels = getRawPixelsForComponent(i);
         Image newImage = new DefaultImage(pixels, pixelWidth_, pixelHeight_,
               bytesPerPixel_ / numComponents_, 1,
               coords_.copy().channel(coords_.getChannel() + i).build(),
               metadata_);
         result.add(newImage);
      }
      return result;
   }

   /**
    * Split this multi-component Image into several single-component Images
    * and add them to the Datastore. They will be positioned based on our
    * current index, with the channel incrementing by 1 for each new
    * component.
    */
   public List<Image> splitMultiComponentIntoStore(Datastore store) throws DatastoreFrozenException, DatastoreRewriteException {
      List<Image> result = splitMultiComponent();
      for (Image image : result) {
         store.putImage(image);
      }
      return result;
   }

   /**
    * For backwards compatibility, convert to TaggedImage.
    */
   public TaggedImage legacyToTaggedImage() {
      JSONObject tags = metadata_.toJSON();
      // Fill in fields that we know about and that our metadata doesn't.
      try {
         MDUtils.setFrameIndex(tags, coords_.getTime());
         MDUtils.setSliceIndex(tags, coords_.getZ());
         MDUtils.setChannelIndex(tags, coords_.getChannel());
         MDUtils.setPositionIndex(tags, coords_.getStagePosition());
         int type = getImageJPixelType();
         MDUtils.setPixelType(tags, type);
         // Create a redundant copy of index information in a format that
         // lets us store all axis information.
         JSONObject fullCoords = new JSONObject();
         for (String axis : coords_.getAxes()) {
            fullCoords.put(axis, coords_.getIndex(axis));
         }
         // TODO: currently nothing actually consumes this information.
         tags.put(COORDS_TAG, fullCoords);
      }
      catch (JSONException e) {
         ReportingUtils.logError("Unable to set image indices: " + e);
      }
      return new TaggedImage(getRawPixels(), tags);
   }

   /**
    * Given some tags as produced by legacyToTaggedImage(), return the
    * Coords of the corresponding image (by parsing the COORDS_TAG
    * structure).
    */
   public static Coords getCoordsFromTags(JSONObject tags) {
      if (!tags.has(COORDS_TAG)) {
         // No coordinates available.
         return null;
      }
      try {
         Coords.CoordsBuilder builder = new DefaultCoords.Builder();
         JSONObject coords = tags.getJSONObject(COORDS_TAG);
         for (String key : MDUtils.getKeys(coords)) {
            builder.index(key, coords.getInt(key));
         }
         return builder.build();
      }
      catch (JSONException e) {
         ReportingUtils.logError(e, "Unable to retrieve coordinates from tags");
         return null;
      }
   }

   public int getImageJPixelType() {
      int bytesPerPixel = getBytesPerPixel();
      int numComponents = getNumComponents();
      if (bytesPerPixel == 4) {
         if (numComponents == 3) {
            return ImagePlus.COLOR_RGB;
         }
         else {
            return ImagePlus.GRAY32;
         }
      }
      else if (bytesPerPixel == 2) {
         return ImagePlus.GRAY16;
      }
      else if (bytesPerPixel == 1) {
         return ImagePlus.GRAY8;
      }
      ReportingUtils.logError(String.format("Unrecognized pixel type with %d bytes per pixel and %d components", bytesPerPixel, numComponents));
      return -1;
   }

   @Override
   public int getWidth() {
      return pixelWidth_;
   }

   @Override
   public int getHeight() {
      return pixelHeight_;
   }

   @Override
   public int getBytesPerPixel() {
      return bytesPerPixel_;
   }

   @Override
   public int getNumComponents() {
      return numComponents_;
   }

   public String toString() {
      return String.format("<%dx%dx%d image (byte depth %d) at %s>",
            getWidth(), getHeight(), getNumComponents(),
            getBytesPerPixel(), coords_);
   }
}
