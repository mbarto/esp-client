package org.esp.publisher;

import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;

import javax.media.jai.ImageLayout;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.ExtremaDescriptor;

import org.esp.publisher.utils.PublisherUtils;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.data.DataSourceException;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

import com.google.common.primitives.Doubles;
import com.google.inject.Inject;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Polygon;

/**
 * Quick refactor to extract tiff processing for a copy paste job
 * 
 * @author Will Temperley
 * 
 */
public class TiffPublisher extends AbstractFilePublisher {


    static {
        System.setProperty("com.sun.media.jai.disableMediaLib", "true");
    }

    @Inject
    public TiffPublisher(GeoserverRestApi gsr) {
        super(gsr);
    }

    @Override
    protected PublishedFileMeta createMetadata(File tifFile, String layerName)
            throws PublishException, UnknownCRSException {
        
        TiffMeta surface = new TiffMeta();;
        GeoTiffReader gtr = null;
        
        surface.setFile(tifFile);
        try {
            gtr = new GeoTiffReader(tifFile);
            CoordinateReferenceSystem crs = gtr.getCoordinateReferenceSystem();
            setCrs(surface, crs);

            //
            extremaOp(surface, gtr.read(null));

            /*
             * Build the envelope and set to WGS84
             */
            GeneralEnvelope origEnv = gtr.getOriginalEnvelope();
            DirectPosition ll = origEnv.getLowerCorner();
            DirectPosition ur = origEnv.getUpperCorner();

            Envelope e = new Envelope();
            e.expandToInclude(ll.getOrdinate(0), ll.getOrdinate(1));
            e.expandToInclude(ur.getOrdinate(0), ur.getOrdinate(1));
            
            ReferencedEnvelope env = new ReferencedEnvelope(e,crs);
            
            Geometry poly = PublisherUtils.envelopeToWgs84(env);

            if (poly instanceof Polygon) {
                surface.setEnvelope((Polygon) poly);
            }

            /*
             * Figure out the pixel size
             */
            ImageLayout imageLayout = gtr.getImageLayout();
            int imageWidth = imageLayout.getWidth(null);
            int imageHeight = imageLayout.getHeight(null);

            double pixelSizeX = e.getWidth() / imageWidth;
            double pixelSizeY = e.getHeight() / imageHeight;

            surface.setPixelSizeX(pixelSizeX);
            surface.setPixelSizeY(pixelSizeY);

            surface.setMinVal(0d);
            surface.setMaxVal(100d);

            GridCoverage2D gridCoverage2D = gtr.read(null);

            try {
                int nDims = gridCoverage2D.getNumSampleDimensions();
                surface.setNumSampleDimensions(nDims);

                extremaOp(surface, gridCoverage2D);

            } finally {
                gridCoverage2D.dispose(false);
            }

        } catch (DataSourceException e) {
            throw new PublishException("Error opening tif file: " + e.getMessage(), e);
        } catch (FactoryException e) {
            throw new PublishException("Error reading crs from tif file: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PublishException("Error reading tif file: " + e.getMessage(), e);
        } catch (TransformException e) {
            throw new PublishException("Error transforming bbox to WGS84: " + e.getMessage(), e);
        } finally {
            gtr.dispose();
        }
        return surface;
    }

    private void extremaOp(TiffMeta surface, GridCoverage2D gridCoverage2D) {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        RenderedImage img = gridCoverage2D.getRenderedImage();

        RenderedOp extremaOp = ExtremaDescriptor.create(img, null, 10, 10,
                false, 1, null);
        double[] allMins = (double[]) extremaOp.getProperty("minimum");
        min = Doubles.min(allMins);

        double[] allMaxs = (double[]) extremaOp.getProperty("maximum");
        max = Doubles.max(allMaxs);

        surface.setMaxVal(max);
        surface.setMinVal(min);
    }

    @Override
    public boolean publishLayer(String layerName, PublishedFileMeta metadata) {
        return true;
    }

}