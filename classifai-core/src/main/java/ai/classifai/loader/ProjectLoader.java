/*
 * Copyright (c) 2020-2021 CertifAI Sdn. Bhd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.classifai.loader;

import ai.classifai.database.portfolio.PortfolioVerticle;
import ai.classifai.database.versioning.Annotation;
import ai.classifai.database.versioning.ProjectVersion;
import ai.classifai.selector.status.FileSystemStatus;
import ai.classifai.util.data.ImageHandler;
import ai.classifai.util.project.ProjectInfra;
import ai.classifai.wasabis3.WasabiProject;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

/**
 * Class per project for managing the loading of project
 *
 * @author codenamewei
 */
@Slf4j
@Getter
@Setter
@Builder
public class ProjectLoader
{
    private String projectId;
    private String projectName;

    private Integer annotationType;
    private File projectPath;

    private Boolean isProjectNew;
    private Boolean isProjectStarred;

    private ProjectInfra projectInfra;

    //Load an existing project from database
    //After loaded once, this value will be always LOADED so retrieving of project from memory than db
    private ProjectLoaderStatus projectLoaderStatus;

    private ProjectVersion projectVersion;

    @Builder.Default private WasabiProject wasabiProject = null;

    @Builder.Default private List<String> labelList = new ArrayList<>();

    //a list of unique uuid representing number of data points in one project
    @Builder.Default private List<String> sanityUuidList = new ArrayList<>();
    @Builder.Default private List<String> uuidListFromDb = new ArrayList<>();

    //key: data point uuid
    //value: annotation
    @Builder.Default private Map<String, Annotation> uuidAnnotationDict = new HashMap<>();

    @Builder.Default private Boolean isLoadedFrontEndToggle = Boolean.FALSE;

    //used when checking for progress in
    //(1) validity of database data point
    //(2) adding new data point through file/folder
    @Builder.Default private Integer currentUuidMarker = 0;
    @Builder.Default private Integer totalUuidMaxLen = 1;

    //Set to push in unique uuid to prevent recurrence
    //this will eventually port into List<Integer>
    @Builder.Default private Set<String> validUUIDSet = new LinkedHashSet<>();

    //Status when dealing with file/folder opener
    @Builder.Default private FileSystemStatus fileSystemStatus = FileSystemStatus.DID_NOT_INITIATED;

    //list to send the new added datapoints as thumbnails to front end
    @Builder.Default private List<String> fileSysNewUuidList = new ArrayList<>();

    //list to iterate through uuid list from existing database to remove if necessary
    @Builder.Default private List<String> dbListBuffer = new ArrayList<>();
    @Builder.Default private List<String> reloadAdditionList = new ArrayList<>();
    @Builder.Default private List<String> reloadDeletionList = new ArrayList<>();

    @Builder.Default private List<Integer> progressUpdate = Arrays.asList(0, 1);

    @Builder.Default private List<String> unsupportedImageList = new ArrayList<>();

    public String getCurrentVersionUuid()
    {
        return projectVersion.getCurrentVersion().getVersionUuid();
    }

    public void toggleFrontEndLoaderParam()
    {
        if (isProjectNew)
        {
            //update database to be old project
            PortfolioVerticle.updateIsNewParam(projectId);
        }

        isLoadedFrontEndToggle = Boolean.TRUE;
    }

    //loading project from database
    public List<Integer> getProgress()
    {
        List<Integer> progressBar = new ArrayList<>();

        progressBar.add(currentUuidMarker);
        progressBar.add(totalUuidMaxLen);

        return progressBar;
    }

    public void setDbOriUUIDSize(Integer totalUUIDSizeBuffer)
    {
        totalUuidMaxLen = totalUUIDSizeBuffer;

        if (totalUuidMaxLen.equals(0))
        {
            projectLoaderStatus = ProjectLoaderStatus.LOADED;
        }
        else if (totalUuidMaxLen.compareTo(0) < 0)
        {
            log.debug("UUID Size less than 0. UUIDSize: " + totalUUIDSizeBuffer);
            projectLoaderStatus = ProjectLoaderStatus.ERROR;
        }
    }

    public void updateDBLoadingProgress(Integer currentSize)
    {
        currentUuidMarker = currentSize;

        //if done, offload set to list
        if (currentUuidMarker.equals(totalUuidMaxLen))
        {
            sanityUuidList = new ArrayList<>(validUUIDSet);

            validUUIDSet.clear();

            projectLoaderStatus = ProjectLoaderStatus.LOADED;
        }
    }

    public void pushDBValidUUID(String uuid)
    {
        validUUIDSet.add(uuid);
    }

    public void pushFileSysNewUUIDList(String uuid)
    {
        fileSysNewUuidList.add(uuid);
    }

    public void updateLoadingProgress(Integer currentSize)
    {
        currentUuidMarker = currentSize;
        progressUpdate.set(0, currentUuidMarker);

        //if done, offload set to list
        if (currentUuidMarker.equals(totalUuidMaxLen))
        {
            if (fileSysNewUuidList.isEmpty())
            {
                fileSystemStatus = FileSystemStatus.DATABASE_NOT_UPDATED;
            }
            else
            {
                sanityUuidList.addAll(fileSysNewUuidList);
                uuidListFromDb.addAll(fileSysNewUuidList);

                projectVersion.setCurrentVersionUuidList(fileSysNewUuidList);
                projectVersion.setCurrentVersionLabelList(labelList);

                PortfolioVerticle.createNewProject(projectId);

                fileSystemStatus = FileSystemStatus.DATABASE_UPDATED;
            }
        }
    }

    public void uploadUuidFromRootPath(@NonNull String uuid)
    {
        if(!uuidListFromDb.contains(uuid)) uuidListFromDb.add(uuid);

        sanityUuidList.add(uuid);
        reloadAdditionList.add(uuid);

    }

    public void uploadSanityUuidFromConfigFile(@NonNull String uuid)
    {
        sanityUuidList.add(uuid);
    }

    public void resetFileSysProgress(FileSystemStatus currentFileSystemStatus)
    {
        validUUIDSet.clear();
        fileSysNewUuidList.clear();

        currentUuidMarker = 0;
        totalUuidMaxLen = 1;

        progressUpdate = new ArrayList<>(Arrays.asList(currentUuidMarker, totalUuidMaxLen));

        fileSystemStatus = currentFileSystemStatus;
    }

    public void resetReloadingProgress(FileSystemStatus currentFileSystemStatus)
    {
        dbListBuffer = new ArrayList<>(sanityUuidList);
        reloadAdditionList.clear();
        reloadDeletionList.clear();

        currentUuidMarker = 0;
        totalUuidMaxLen = 1;

        progressUpdate = new ArrayList<>(Arrays.asList(currentUuidMarker, totalUuidMaxLen));

        fileSystemStatus = currentFileSystemStatus;
    }

    //updating project progress from reloading it
    public void updateReloadingProgress(Integer currentSize)
    {
        progressUpdate.set(0, currentSize);

        //if done, offload set to list
        if (currentSize.equals(totalUuidMaxLen))
        {
            sanityUuidList.removeAll(dbListBuffer);
            reloadDeletionList = dbListBuffer;

            PortfolioVerticle.updateFileSystemUuidList(projectId);
            fileSystemStatus = FileSystemStatus.DATABASE_UPDATED;
        }
    }


    public void setFileSysTotalUUIDSize(Integer totalUUIDSizeBuffer)
    {
        totalUuidMaxLen = totalUUIDSizeBuffer;
        progressUpdate = Arrays.asList(new Integer[]{0, totalUuidMaxLen});
    }

    public boolean isCloud()
    {
        return wasabiProject != null;
    }

    public void initFolderIteration()
    {
        try
        {
            if(!ImageHandler.loadProjectRootPath(this, true))
            {
                // Get example image from metadata
                File srcImgFile = Paths.get(".", "metadata", "classifai_overview.png").toFile();
                File destImageFile = Paths.get(projectPath.getAbsolutePath(), "example_img.png").toFile();
                FileUtils.copyFile(srcImgFile, destImageFile);
                log.info("Empty folder. Example image added.");

                // Run loadProjectRootPath again
                if(!ImageHandler.loadProjectRootPath(this, true))
                {
                    log.debug("Loading files in project folder failed");
                }
            }
        }
        catch(IOException e)
        {
            log.info("Error while copying file: ", e);
        }
    }


}