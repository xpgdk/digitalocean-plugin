/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 robert.gruendler@dubture.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.dubture.jenkins.digitalocean;

import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Image;
import com.myjeeva.digitalocean.pojo.Images;
import com.myjeeva.digitalocean.pojo.Key;
import com.myjeeva.digitalocean.pojo.Region;
import com.myjeeva.digitalocean.pojo.Regions;
import com.myjeeva.digitalocean.pojo.Size;
import com.myjeeva.digitalocean.pojo.Sizes;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProperty;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Lists.newArrayList;

/**
 * A {@link com.dubture.jenkins.digitalocean.SlaveTemplate} represents the configuration values for creating a
 * new slave via a DigitalOcean droplet.
 *
 * Holds things like Image ID, sizeId and region used for the specific droplet.
 *
 * The {@link SlaveTemplate#provision(com.myjeeva.digitalocean.impl.DigitalOceanClient, String, String, Integer, hudson.util.StreamTaskListener)} method
 * is the main entry point to create a new droplet via the DigitalOcean API when a new slave needs to be provisioned.
 *
 * @author robert.gruendler@dubture.com
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {

    private static final String DROPLET_PREFIX = "jenkins-";

    private final String labelString;

    private final int idleTerminationInMinutes;

    private final String labels;

    /**
     * The Image to be used for the droplet.
     */
    private final String imageId;

    /**
     * The specified droplet sizeId.
     */
    private final String sizeId;

    /**
     * The region for the droplet.
     */
    private final String regionId;

    /**
     * The remote user to use.
     */
    private final String remoteUser;

    /**
     * The remote path to use for the Jenkins deployment.
     */
    private final String remotePath;

    private transient Set<LabelAtom> labelSet;

    protected transient Cloud parent;

    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());


    /**
     * Data is injected from the global Jenkins configuration via jelly.
     * @param imageId an image slug e.g. "ubuntu-14-04-x64"
     * @param sizeId the image size e.g. "512mb" or "1gb"
     * @param regionId the region e.g. "nyc1"
     * @param idleTerminationInMinutes how long to wait before destroying a slave
     * @param labelString the label for this slave
     * @param remoteUser remote user
     * @param remotePath remote path
     */
    @DataBoundConstructor
    public SlaveTemplate(String imageId, String sizeId, String regionId, String idleTerminationInMinutes, String labelString, String remoteUser, String remotePath) {
        LOGGER.log(Level.INFO, "Creating SlaveTemplate with imageId = {0}, sizeId = {1}, regionId = {2}", new Object[] { imageId, sizeId, regionId});
        this.imageId = imageId;
        this.sizeId = sizeId;
        this.regionId = regionId;

        this.idleTerminationInMinutes = Integer.parseInt(idleTerminationInMinutes);
        this.labelString = labelString;
        this.labels = Util.fixNull(labelString);
        this.remoteUser = remoteUser;
        this.remotePath = remotePath;

        readResolve();
    }

    protected Object readResolve() {
        labelSet = Label.parse(labels);
        return this;
    }

    /**
     * Creates a new droplet on DigitalOcean to be used as a Jenkins slave.
     *
     * @param apiClient the v2 API client to use
     * @param privateKey the RSA private key to use
     * @param sshKeyId the SSH key name name to use
     * @param listener the listener on which to report progress
     * @return the provisioned {@link Slave}
     * @throws IOException
     * @throws RequestUnsuccessfulException
     * @throws Descriptor.FormException
     */
    public Slave provision(DigitalOceanClient apiClient, String dropletName, String privateKey, Integer sshKeyId, StreamTaskListener listener) throws IOException, RequestUnsuccessfulException, Descriptor.FormException {

        LOGGER.log(Level.INFO, "Provisioning slave...");

        PrintStream logger = listener.getLogger();
        try {
            logger.println("Starting to provision digital ocean droplet using image: " + imageId + ", region: " + regionId + ", sizeId: " + sizeId);

            // create a new droplet
            // TODO: set the data from the UI
            Droplet droplet = new Droplet();
            droplet.setName(dropletName);
            droplet.setSize(sizeId);
            droplet.setRegion(new Region(regionId));
            droplet.setImage(new Image(imageId));
            droplet.setKeys(newArrayList(new Key(sshKeyId)));

            logger.println("Creating slave with new droplet " + dropletName);

            Droplet createdDroplet = apiClient.createDroplet(droplet);
            return newSlave(createdDroplet, privateKey);
        } catch (Exception e) {
            e.printStackTrace(logger);
            throw new AssertionError();
        }
    }

    /**
     * Create a new {@link com.dubture.jenkins.digitalocean.Slave} from the given {@link com.myjeeva.digitalocean.pojo.Droplet}
     * @param droplet the droplet being created
     * @param privateKey the RSA private key being used
     * @return the provisioned {@link Slave}
     * @throws IOException
     * @throws Descriptor.FormException
     */
    private Slave newSlave(Droplet droplet, String privateKey) throws IOException, Descriptor.FormException {
        LOGGER.log(Level.INFO, "Creating new slave...");
        return new Slave(
                getParent().getName(),
                droplet.getName(),
                "Computer running on DigitalOcean with name: " + droplet.getName(),
                droplet.getId(),
                privateKey,
                remotePath,
                remoteUser,
                1,
                idleTerminationInMinutes,
                Node.Mode.NORMAL,
                labels,
                new ComputerLauncher(),
                new RetentionStrategy(),
                Collections.<NodeProperty<?>>emptyList(),
                "",
                ""
        );
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        public ListBoxModel doFillSizeIdItems(@RelativePath("..") @QueryParameter String authToken) throws Exception {

            List<Size> availableSizes = getAvailableSizes(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Size size : availableSizes) {
                model.add(size.getSlug());
            }

            return model;
        }

        private static List<Size> getAvailableSizes(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
            DigitalOceanClient client = new DigitalOceanClient(authToken);

            List<Size> availableSizes = new ArrayList<Size>();
            int page = 0;
            Sizes sizes;

            do {
                page += 1;
                sizes = client.getAvailableSizes(page);
                availableSizes.addAll(sizes.getSizes());
            }
            while (sizes.getMeta().getTotal() > page);

            return availableSizes;
        }

        public ListBoxModel doFillImageIdItems(@RelativePath("..") @QueryParameter String authToken) throws Exception {

            List<Image> availableSizes = getAvailableImages(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Image image : availableSizes) {
                model.add(image.getDistribution() + " " + image.getName(), image.getId().toString());
            }

            return model;
        }

        private static List<Image> getAvailableImages(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
            DigitalOceanClient client = new DigitalOceanClient(authToken);

            List<Image> availableSizes = new ArrayList<Image>();
            Images images;
            int page = 0;

            do {
                page += 1;
                images = client.getAvailableImages(page);
                availableSizes.addAll(images.getImages());
            }
            while (images.getMeta().getTotal() > page);

            return availableSizes;
        }

        public ListBoxModel doFillRegionIdItems(@RelativePath("..") @QueryParameter String authToken) throws Exception {

            List<Region> availableSizes = getAvailableRegions(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Region region : availableSizes) {
                model.add(region.getName(), region.getSlug());
            }

            return model;
        }

        private static List<Region> getAvailableRegions(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
            DigitalOceanClient client = new DigitalOceanClient(authToken);

            List<Region> availableRegions = new ArrayList<Region>();
            Regions regions;
            int page = 0;

            do {
                page += 1;
                regions = client.getAvailableRegions(page);
                availableRegions.addAll(regions.getRegions());
            }
            while (regions.getMeta().getTotal() > page);

            return availableRegions;
        }

    }

    public Descriptor<SlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public String createDropletName() {
        return DROPLET_PREFIX + UUID.randomUUID().toString();
    }

    public String getSizeId() {
        return sizeId;
    }

    public String getRegionId() {
        return regionId;
    }

    public String getLabels() {
        return labels;
    }

    public String getLabelString() {
        return labelString;
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public Cloud getParent() {
        return parent;
    }

    public String getImageId() {
        return imageId;
    }

    public int getNumExecutors() {
        return 1;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }

    public String getRemoteUser() {
        return remoteUser;
    }

    public String getRemotePath() {
        return remotePath;
    }
}
