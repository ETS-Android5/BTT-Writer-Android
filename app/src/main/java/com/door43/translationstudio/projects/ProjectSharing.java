package com.door43.translationstudio.projects;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.door43.translationstudio.R;
import com.door43.translationstudio.SettingsActivity;
import com.door43.translationstudio.git.Repo;
import com.door43.translationstudio.projects.imports.ChapterImport;
import com.door43.translationstudio.projects.imports.FileImport;
import com.door43.translationstudio.projects.imports.FrameImport;
import com.door43.translationstudio.projects.imports.ImportRequestInterface;
import com.door43.translationstudio.projects.imports.ProjectImport;
import com.door43.translationstudio.projects.imports.TranslationImport;
import com.door43.translationstudio.spannables.NoteSpan;
import com.door43.translationstudio.util.FileUtilities;
import com.door43.translationstudio.util.Logger;
import com.door43.translationstudio.util.MainContext;
import com.door43.translationstudio.util.Security;
import com.door43.translationstudio.util.Zip;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class handles all the features for importing and exporting projects.
 * TODO: we need to pull all of the existing import and export code into this class.
 */
public class ProjectSharing {

    /**
     * Exports a json array of projects and the translations available.
     * This information is used to present a browsable library to the user from which
     * they may select different translations on which to perform actions
     * @param projects an array of projects to be inclued in the library
     * @return
     */
    public static String generateLibrary(Project[] projects) {
        return generateLibrary(projects, null);
    }

    /**
     * Exports a json array of projects and the translations available.
     * This information is used to present a browsable library to the user from which
     * they may select different translations on which to perform actions
     * @param projects an array of projects to be inclued in the library
     * @param preferredLibraryLanguages the preferred language(S) in which the library will be generated. The first available language will be used by order of index on per project basis.
     * @return
     */
    public static String generateLibrary(Project[] projects, List<SourceLanguage> preferredLibraryLanguages) {
        JSONArray libraryJson = new JSONArray();

        for(Project p:projects) {
            if(p.isTranslatingGlobal()) {
                JSONObject json = new JSONObject();
                try {
                    json.put("id", p.getId());

                    // for better readability we attempt to give the project details in one of the preferred languages
                    SourceLanguage libraryLanguage = p.getSelectedSourceLanguage();
                    if(preferredLibraryLanguages != null && preferredLibraryLanguages.size() > 0) {
                        for(SourceLanguage pref:preferredLibraryLanguages) {
                            SourceLanguage l = p.getSourceLanguage(pref.getId());
                            if(l != null) {
                                libraryLanguage = l;
                                break;
                            }
                        }
                    }

                    // project info
                    JSONObject projectInfoJson = new JSONObject();
                    projectInfoJson.put("name", p.getTitle(libraryLanguage));
                    projectInfoJson.put("description", p.getDescription(libraryLanguage));
                    // NOTE: since we are only providing the project details in a single source language we don't need to include the meta id's
                    PseudoProject[] pseudoProjects = p.getSudoProjects();
                    JSONArray sudoProjectsJson = new JSONArray();
                    for(PseudoProject sp: pseudoProjects) {
                        sudoProjectsJson.put(sp.getTitle(libraryLanguage));
                    }
                    projectInfoJson.put("meta", sudoProjectsJson);
                    json.put("project", projectInfoJson);

                    // library language
                    JSONObject libraryLanguageJson = new JSONObject();
                    libraryLanguageJson.put("slug", libraryLanguage.getId());
                    libraryLanguageJson.put("name", libraryLanguage.getName());
                    if(libraryLanguage.getDirection() == Language.Direction.RightToLeft) {
                        libraryLanguageJson.put("direction", "rtl");
                    } else {
                        libraryLanguageJson.put("direction", "ltr");
                    }
                    json.put("language", libraryLanguageJson);

                    // target languages for which translations are available
                    Language[] targetLanguages = p.getActiveTargetLanguages();
                    JSONArray languagesJson = new JSONArray();
                    for(Language l:targetLanguages) {
                        JSONObject langJson = new JSONObject();
                        langJson.put("slug", l.getId());
                        langJson.put("name", l.getName());
                        if(l.getDirection() == Language.Direction.RightToLeft) {
                            langJson.put("direction", "rtl");
                        } else {
                            langJson.put("direction", "ltr");
                        }
                        languagesJson.put(langJson);
                    }
                    json.put("target_languages", languagesJson);
                    libraryJson.put(json);
                } catch (JSONException e) {
                    Logger.e(ProjectSharing.class.getName(), "Failed to generate a library record for the project "+p.getId(), e);
                }
            }
        }
        return libraryJson.toString();
    }

    /**
     * Prepares a translationStudio project archive for import.
     * This leaves files around so be sure to run the importcleanup when finished.
     * @param archive the archive that will be imported
     * @return true if the import was successful
     */
    public static ProjectImport[] prepareArchiveImport(File archive) {
        Map<String, ProjectImport> projectImports = new HashMap<String, ProjectImport>();

        // validate extension
        String[] name = archive.getName().split("\\.");
        if(name[name.length - 1].equals(Project.PROJECT_EXTENSION)) {
            long timestamp = System.currentTimeMillis();
            File extractedDir = new File(MainContext.getContext().getCacheDir() + "/" + MainContext.getContext().getResources().getString(R.string.imported_projects_dir) + "/" + timestamp);

            try {
                Zip.unzip(archive, extractedDir);
            } catch (IOException e) {
                FileUtilities.deleteRecursive(extractedDir);
                Logger.e(Project.class.getName(), "failed to extract the project archive", e);
                return projectImports.values().toArray(new ProjectImport[projectImports.size()]);
            }

            File manifest = new File(extractedDir, "manifest.json");
            if(manifest.exists() && manifest.isFile()) {
                try {
                    JSONObject manifestJson = new JSONObject(FileUtils.readFileToString(manifest));
                    // NOTE: the manifest contains extra information that we are not using right now
                    if(manifestJson.has("projects")) {
                        JSONArray projectsJson = manifestJson.getJSONArray("projects");
                        for(int i=0; i<projectsJson.length(); i++) {
                            JSONObject projJson = projectsJson.getJSONObject(i);
                            if(projJson.has("path") && projJson.has("project") && projJson.has("target_language")) {
                                // create new or load existing project import
                                ProjectImport pi = new ProjectImport(projJson.getString("project"), extractedDir);
                                if(projectImports.containsKey(pi.projectId)) {
                                    pi = projectImports.get(pi.projectId);
                                } else {
                                    projectImports.put(pi.projectId, pi);
                                }
                                // prepare the translation import
                                boolean hadTranslationWarnings = prepareImport(pi, projJson.getString("target_language"), new File(extractedDir, projJson.getString("path")));
                                if(hadTranslationWarnings) {
                                    pi.setWarning("Some translations already exist");
                                }
                            }
                        }
                    }
                } catch (JSONException e) {
                    Logger.e(Project.class.getName(), "failed to parse the manifest", e);
                } catch (IOException e) {
                    Logger.e(Project.class.getName(), "failed to read the manifest file", e);
                }
            }
        }
        return projectImports.values().toArray(new ProjectImport[projectImports.size()]);
    }

    /**
     * Imports a Translation Studio Project from a directory
     * This is a legacy import method for archives exported by 2.0.2 versions of the app.
     * @param archiveFile the directory that will be imported
     * @return
     */
    public static boolean prepareLegacyArchiveImport(File archiveFile) {
        String[] name = archiveFile.getName().split("\\.");
        if(name[name.length - 1].equals("zip")) {
            // extract archive
            long timestamp = System.currentTimeMillis();
            File extractedDirectory = new File(MainContext.getContext().getCacheDir() + "/" + MainContext.getContext().getResources().getString(R.string.imported_projects_dir) + "/" + timestamp);
            File importDirectory;
            Boolean success = false;
            try {
                // extract into a timestamped directory so we don't accidently throw files all over the place
                Zip.unzip(archiveFile, extractedDirectory);
                File[] files = extractedDirectory.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File file, String s) {
                        return ProjectSharing.validateProjectArchiveName(s);
                    }
                });
                if(files.length == 1) {
                    importDirectory = files[0];
                } else {
                    // malformed archive
                    FileUtilities.deleteRecursive(extractedDirectory);
                    return false;
                }
            } catch (IOException e) {
                FileUtilities.deleteRecursive(extractedDirectory);
                Logger.e(Project.class.getName(), "failed to extract the legacy project archive", e);
                return false;
            }

            // read project info
            TranslationArchiveInfo translationInfo = getTranslationArchiveInfo(importDirectory.getName());
            if(translationInfo != null) {
                ProjectImport pi = new ProjectImport(translationInfo.projectId, extractedDirectory);
                prepareImport(pi, translationInfo.languageId, importDirectory);
                // TODO: for now we are just blindly importing legacy projects (dangerous). We'll need to update this method as well as the DokuWiki import method in order to properly handle these legacy projects
                importProject(pi);
                cleanImport(pi);
            }
            FileUtilities.deleteRecursive(extractedDirectory);
            return success;
        } else {
            return false;
        }
    }

    /**
     * Checks if the project archive is named properly
     * @deprecated this is legacy code for old import methods
     * @param name
     * @return
     */
    public static boolean validateProjectArchiveName(String name) {
        String[] fields = name.toLowerCase().split("-");
        return fields.length == 3 && fields[0].equals(Project.GLOBAL_PROJECT_SLUG);
    }

    /**
     * Returns information about the translation archive
     * @deprecated this is legacy code for old import methods
     * @param archiveName
     * @return
     */
    public static TranslationArchiveInfo getTranslationArchiveInfo(String archiveName) {
        String[] parts = archiveName.split("_");
        String name = parts[0];
        // TRICKY: older version of the app mistakenly included the leading directory separator
        while(name.startsWith("/")) {
            name = name.substring(name.indexOf("/"));
        }
        if(validateProjectArchiveName(name)) {
            String[] fields = name.toLowerCase().split("-");
            return new TranslationArchiveInfo(fields[0], fields[1], fields[2]);
        }
        return null;
    }

    /**
     * Stores information about a translation archive
     * @deprecated this is legacy code for the old import methods
     */
    public static class TranslationArchiveInfo {
        public final String globalProjectId;
        public final String projectId;
        public final String languageId;

        public TranslationArchiveInfo(String globalProjectId, String projectId, String languageId) {
            this.globalProjectId = globalProjectId;
            this.projectId = projectId;
            this.languageId = languageId;
        }

        public Project getProject() {
            return MainContext.getContext().getSharedProjectManager().getProject(projectId);
        }

        public Language getLanguage() {
            return MainContext.getContext().getSharedProjectManager().getLanguage(languageId);
        }
    }

    /**
     * Performs some checks on a project to make sure it can be imported.
     * @param projectImport the import request for the project
     * @param languageId the the language id for the translatoin
     * @param projectDir the directory of the project translation that will be imported
     */
    private static boolean  prepareImport(final ProjectImport projectImport, final String languageId, File projectDir) {
        final TranslationImport translationImport = new TranslationImport(languageId, projectDir);
        projectImport.addTranslationImport(translationImport);
        boolean hadTranslationWarnings = false;

        // locate existing project
        final Project p = MainContext.getContext().getSharedProjectManager().getProject(projectImport.projectId);
//        Language l = MainContext.getContext().getSharedProjectManager().getLanguage(languageId);
        if(p == null) {
            // TODO: eventually we'd like to support importing the project source as well
            Logger.i(Project.class.getName(), "Missing project source");
        }
        // change the target language so we can easily reference the local translation
//            Language originalTargetLanguage = p.getSelectedTargetLanguage();
//            p.setSelectedTargetLanguage(languageId);

        // look through items to import
        if(Project.isTranslating(projectImport.projectId, languageId)) {
            hadTranslationWarnings = true;
            // the project already exists

            // TODO: we should look at the md5 contents of the files to determine any differences. if files are identical the import should mark them as approved
            boolean hadChapterWarnings = false;

            // read chapters to import
            String[] chapterIds = projectDir.list(new FilenameFilter() {
                @Override
                public boolean accept(File file, String s) {
                    return !s.equals(".git") && file.isDirectory();
                }
            });
            for(String chapterId:chapterIds) {
//                    Chapter c = p.getChapter(chapterId);
//                    if(c != null) {
                ChapterImport chapterImport = new ChapterImport(chapterId, String.format(MainContext.getContext().getResources().getString(R.string.label_chapter_title_detailed), chapterId));
                if(Chapter.isTranslating(projectImport.projectId, languageId, chapterId)) {
                    chapterImport.setWarning("Importing will override our existing translation");
                    hadChapterWarnings = true;
                }
                translationImport.addChapterImport(chapterImport);
                boolean hadFrameWarnings = false;

                // read chapter title and reference
                File titleFile = new File(projectDir, chapterId + "/title.txt");
                if(titleFile.exists()) {
                    FileImport fileImport = new FileImport("title", MainContext.getContext().getResources().getString(R.string.chapter_title_field));
                    chapterImport.addFileImport(fileImport);
                    // check if chapter title translation exists
                    File currentTitleFile = new File(Chapter.getTitlePath(projectImport.projectId, languageId, chapterId));
                    if(currentTitleFile.exists()) {
                        fileImport.setWarning("Importing will override our existing translation");
                    }
                }
                File referenceFile = new File(projectDir, chapterId + "/reference.txt");
                if(referenceFile.exists()) {
                    FileImport fileImport = new FileImport("reference", MainContext.getContext().getResources().getString(R.string.chapter_reference_field));
                    chapterImport.addFileImport(fileImport);
                    // check if chapter reference translation exists
                    File currentReferenceFile = new File(Chapter.getReferencePath(projectImport.projectId, languageId, chapterId));
                    if(currentReferenceFile.exists()) {
                        fileImport.setWarning("Importing will override our existing translation");
                    }
                }

                // read frames to import
                String[] frameFileNames = new File(projectDir, chapterId).list(new FilenameFilter() {
                    @Override
                    public boolean accept(File file, String s) {
                        return !s.equals("title.txt") && !s.equals("reference.txt");
                    }
                });
                for(String frameFileName:frameFileNames) {
                    String[] pieces = frameFileName.split("\\.");
                    if(pieces.length != 2) {
                        Logger.w(Project.class.getName(), "Unexpected file in frame import "+frameFileName);
                        continue;
                    }
                    String frameId = pieces[0];
                    FrameImport frameImport = new FrameImport(frameId, String.format(MainContext.getContext().getResources().getString(R.string.label_frame_title_detailed), frameId));
                    chapterImport.addFrameImport(frameImport);

                    // check if frame translation exists
                    File currentFrameFile = new File(Frame.getFramePath(projectImport.projectId, languageId, chapterId, frameId));
                    if(currentFrameFile.exists()) {
                        frameImport.setWarning("Importing will override our existing translation");
                        hadFrameWarnings = true;
                    }
                }

                if(hadFrameWarnings) {
                    chapterImport.setWarning("Importing will override our existing translation");
                }
//                    } else {
//                        // the import source does not match the source on this device.
//                        ChapterImport chapterRequest = new ChapterImport(chapterId, chapterId + " - unknown");
//                        chapterRequest.setError("Missing source");
//                        translationImport.addChapterImport(chapterRequest);
//                        Logger.e(Project.class.getName(), "Missing source for chapter "+chapterId+". Cannot import into project "+projectImport.projectId+" for language "+languageId);
//                        hadChapterWarnings = true;
//                    }
            }

            if(hadChapterWarnings) {
                translationImport.setWarning("Importing will override our existing translation");
            }
        }
        // restore original target language
//            p.setSelectedTargetLanguage(originalTargetLanguage.getId());
//        } else {
//            // new project source
//            // TODO: eventually we should check if the import includes the source text as well. Then this should just be a warning. Letting the user know that the source will be imported as well.
//            translationImport.setError("Missing project source");
//        }
        return hadTranslationWarnings;
    }

    /**
     * Performs the actual import of the project
     * @param request the project import request
     * @return true if the import did not encounter any errors
     */
    public static boolean importProject(ProjectImport request) {
        boolean hadErrors = false;
        if(request.getError() == null) {
//            if (p != null) {
            ArrayList<ImportRequestInterface> translationRequests = request.getChildImportRequests().getAll();
            // translations
            for (TranslationImport ti : translationRequests.toArray(new TranslationImport[translationRequests.size()])) {
                if(ti.getError() == null) {
                    File repoDir = new File(Project.getRepositoryPath(request.projectId, ti.languageId));
                    if(repoDir.exists()) {
                        ArrayList<ImportRequestInterface> chapterRequests = ti.getChildImportRequests().getAll();
                        // chapters
                        for (ChapterImport ci : chapterRequests.toArray(new ChapterImport[chapterRequests.size()])) {
                            if (ci.getError() == null) {
                                ArrayList<ImportRequestInterface> frameRequests = ci.getChildImportRequests().getAll();
                                for (ImportRequestInterface r : frameRequests) {
                                    if(r.getClass().getName().equals(FrameImport.class.getName())) {
                                        // frames
                                        if (r.isApproved()) {
                                            FrameImport fi = (FrameImport)r;
                                            // import frame
                                            File destFile = new File(Frame.getFramePath(request.projectId, ti.languageId, ci.chapterId, fi.frameId));
                                            File srcFile = new File(ti.translationDirectory, ci.chapterId + "/" + fi.frameId + ".txt");
                                            if (destFile.exists()) {
                                                destFile.delete();
                                            }
                                            destFile.getParentFile().mkdirs();
                                            if (!FileUtilities.moveOrCopy(srcFile, destFile)) {
                                                Logger.e(Project.class.getName(), "Failed to import frame");
                                                hadErrors = true;
                                            }
                                        }
                                    } else if(r.getClass().getName().equals(FileImport.class.getName())) {
                                        // title and reference
                                        if(r.isApproved()) {
                                            FileImport fi = (FileImport)r;
                                            if(fi.getId().equals("title")) {
                                                // import title
                                                File destFile = new File(Chapter.getTitlePath(request.projectId, ti.languageId, ci.chapterId));
                                                File srcFile = new File(ti.translationDirectory, ci.chapterId + "/title.txt");
                                                if (destFile.exists()) {
                                                    destFile.delete();
                                                }
                                                destFile.getParentFile().mkdirs();
                                                if (!FileUtilities.moveOrCopy(srcFile, destFile)) {
                                                    Logger.e(Project.class.getName(), "Failed to import chapter title");
                                                    hadErrors = true;
                                                }
                                            } else if(fi.getId().equals("reference")) {
                                                // import reference
                                                File destFile = new File(Chapter.getReferencePath(request.projectId, ti.languageId, ci.chapterId));
                                                File srcFile = new File(ti.translationDirectory, ci.chapterId + "/reference.txt");
                                                if (destFile.exists()) {
                                                    destFile.delete();
                                                }
                                                destFile.getParentFile().mkdirs();
                                                if (!FileUtilities.moveOrCopy(srcFile, destFile)) {
                                                    Logger.e(Project.class.getName(), "Failed to import chapter reference");
                                                    hadErrors = true;
                                                }
                                            } else {
                                                Logger.w(Project.class.getName(), "Unknown file import request. Expecting title or reference but found "+fi.getId());
                                            }
                                        }
                                    } else {
                                        Logger.w(Project.class.getName(), "Unknown import request. Expecting FrameImport or FileImport but found "+r.getClass().getName());
                                    }
                                }
                            }
                        }
                    } else {
                        // import the new project
                        try {
                            FileUtils.moveDirectory(ti.translationDirectory, repoDir);
                        } catch (IOException e) {
                            Logger.e(Project.class.getName(), "failed to import the project directory", e);
                            hadErrors = true;
                            continue;
                        }
                    }
                    // causes the ui to reload the fresh content from the disk
                    Language l = MainContext.getContext().getSharedProjectManager().getLanguage(ti.languageId);
                    l.touch();
                }
            }
//            } else {
//                Logger.i(Project.class.getName(), "Importing projects with missing source is not currently supported");
//                // TODO: create a new project and add it to the project manager. This will require the existance of the project source in the archive.
//            }

            // commit changes if this was an existing project
            Project p = MainContext.getContext().getSharedProjectManager().getProject(request.projectId);
            if(p != null) {
                p.commit(null);
            }
        }
        return !hadErrors;
    }

    /**
     * This performs house cleaning operations after a project has been imported.
     * You should still run this even if you just prepared the import but didn't actually import
     * because some files get extracted during the process.
     * @param request the import request that will be cleaned
     */
    public static void cleanImport(ProjectImport request) {
        if(request.importDirectory.exists()) {
            FileUtilities.deleteRecursive(request.importDirectory);
        }
    }

    /**
     * This performs house cleaning operations after a project has been imported.
     * You should still run this even if you just prepared the import but didn't actually import
     * because some files get extracted during the process.
     * @param requests the import requests that will be cleaned
     */
    public static void cleanImport(ProjectImport[] requests) {
        for(ProjectImport pi:requests) {
            cleanImport(pi);
        }
    }

    /**
     * Exports the project with the currently selected target language as a translationStudio project
     * This is process heavy and should not be ran on the main thread.
     * @param p the project to export
     * @return the path to the export archive
     * @throws IOException
     */
    public static String export(Project p) throws IOException {
        return export(p, new Language[]{p.getSelectedTargetLanguage()});
    }

    /**
     * Exports the project in multiple languages as a translationStudio project.
     * This is process heavy and should not be ran on the main thread.
     * @param p the project to export
     * @param languages an array of target languages that will be exported
     * @return the path to the export archive
     */
    public static String export(Project p, Language[] languages) throws IOException {
        Context context = MainContext.getContext();
        File exportDir = new File(MainContext.getContext().getCacheDir() + "/" + MainContext.getContext().getResources().getString(R.string.exported_projects_dir));
        File stagingDir = new File(exportDir, System.currentTimeMillis() + "");
        ArrayList<File> zipList = new ArrayList<File>();
        File manifestFile = new File(stagingDir, "manifest.json");
        JSONObject manifestJson = new JSONObject();
        JSONArray projectsJson = new JSONArray();
        stagingDir.mkdirs();
        Boolean stagingSucceeded = true;
        String gitCommit = "";
        String archivePath = "";

        // prepare manifest
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            manifestJson.put("generator", "translationStudio");
            manifestJson.put("version", pInfo.versionCode);
            manifestJson.put("timestamp", System.currentTimeMillis());
        } catch (JSONException e) {
            Logger.e(ProjectSharing.class.getName(), "failed to add to json object", e);
            return archivePath;
        } catch (PackageManager.NameNotFoundException e) {
            Logger.e(ProjectSharing.class.getName(), "failed to get the package name", e);
            return archivePath;
        }

        // stage all the translations
        for(Language l:languages) {
            String projectComplexName = Project.GLOBAL_PROJECT_SLUG + "-" + p.getId() + "-" + l.getId();
            String repoPath = p.getRepositoryPath(p.getId(), l.getId());
            // commit changes to repo
            Repo repo = new Repo(repoPath);
            try {
                // only commit if the repo is dirty
                if(!repo.getGit().status().call().isClean()) {
                    // add
                    AddCommand add = repo.getGit().add();
                    add.addFilepattern(".").call();

                    // commit
                    CommitCommand commit = repo.getGit().commit();
                    commit.setAll(true);
                    commit.setMessage("auto save");
                    commit.call();
                }
            } catch (Exception e) {
                stagingSucceeded = false;
                continue;
            }

            // TRICKY: this has to be read after we commit changes to the repo
            gitCommit += p.getLocalTranslationVersion(l);

            // update manifest
            JSONObject translationJson = new JSONObject();
            try {
                translationJson.put("global_identifier", Project.GLOBAL_PROJECT_SLUG);
                translationJson.put("project", p.getId());
                translationJson.put("title", p.getTitle());
                translationJson.put("target_language", l.getId());
                translationJson.put("source_language", p.getSelectedSourceLanguage().getId());
                translationJson.put("git_commit", gitCommit);
                translationJson.put("path", projectComplexName);
            } catch (JSONException e) {
                Logger.e(ProjectSharing.class.getName(), "failed to add to json object", e);
                return archivePath;
            }
            projectsJson.put(translationJson);

            zipList.add(new File(repoPath));
        }
        String signature = Security.md5(gitCommit);
        String tag = signature.substring(0, 10);

        // close manifest
        try {
            manifestJson.put("projects", projectsJson);
            manifestJson.put("signature", signature);
        } catch (JSONException e) {
            Logger.e(ProjectSharing.class.getName(), "failed to add to json object", e);
            return archivePath;
        }
        FileUtils.write(manifestFile, manifestJson.toString());
        zipList.add(manifestFile);

        // zip
        if(stagingSucceeded) {
            File outputZipFile = new File(exportDir, Project.GLOBAL_PROJECT_SLUG + "-" + p.getId() + "_" + tag + "." + Project.PROJECT_EXTENSION);

            // create the archive if it does not already exist
            if(!outputZipFile.exists()) {
                Zip.zip(zipList.toArray(new File[zipList.size()]), outputZipFile);
            }

            archivePath = outputZipFile.getAbsolutePath();
        }

        // clean up old exports. Android should do this automatically, but we'll make sure
        File[] cachedExports = exportDir.listFiles();
        if(cachedExports != null) {
            for(File f:cachedExports) {
                // trash cached files that are more than 12 hours old.
                if(System.currentTimeMillis() - f.lastModified() > 1000 * 60 * 60 * 12) {
                    if(f.isFile()) {
                        f.delete();
                    } else {
                        FileUtilities.deleteRecursive(f);
                    }
                }
            }
        }

        // clean up staging area
        FileUtilities.deleteRecursive(stagingDir);

        return archivePath;
    }

    /**
     * Exports the project with the currently selected target language in DokuWiki format
     * This is a process heavy method and should not be ran on the main thread
     * TODO: we need to update this so we don't include the root directory. We already support the new method (no root dir) as well as provide legacy suport for importing this format.
     * @param p the project to export
     * @return the path to the export archive
     */
    public static String exportDW(Project p) throws IOException {
        String projectComplexName = Project.GLOBAL_PROJECT_SLUG + "-" + p.getId() + "-" + p.getSelectedTargetLanguage().getId();
        File exportDir = new File(MainContext.getContext().getCacheDir() + "/" + MainContext.getContext().getResources().getString(R.string.exported_projects_dir));
        Boolean commitSucceeded = true;

        Pattern pattern = Pattern.compile(NoteSpan.REGEX_OPEN_TAG + "((?!" + NoteSpan.REGEX_CLOSE_TAG + ").)*" + NoteSpan.REGEX_CLOSE_TAG);
        Pattern defPattern = Pattern.compile("def=\"(((?!\").)*)\"");
        exportDir.mkdirs();

        // commit changes to repo
        Repo repo = new Repo(p.getRepositoryPath());
        try {
            // only commit if the repo is dirty
            if(!repo.getGit().status().call().isClean()) {
                // add
                AddCommand add = repo.getGit().add();
                add.addFilepattern(".").call();

                // commit
                CommitCommand commit = repo.getGit().commit();
                commit.setAll(true);
                commit.setMessage("auto save");
                commit.call();
            }
        } catch (Exception e) {
            commitSucceeded = false;
        }

        // TRICKY: this has to be read after we commit changes to the repo
        String translationVersion = p.getLocalTranslationVersion();
        File outputZipFile = new File(exportDir, projectComplexName + "_" + translationVersion + ".zip");
        File outputDir = new File(exportDir, projectComplexName + "_" + translationVersion);

        // clean up old exports
        String[] cachedExports = exportDir.list();
        for(int i=0; i < cachedExports.length; i ++) {
            String[] pieces = cachedExports[i].split("_");
            if(pieces[0].equals(projectComplexName) && !pieces[1].equals(translationVersion)) {
                File oldDir = new File(exportDir, cachedExports[i]);
                FileUtilities.deleteRecursive(oldDir);
            }
        }

        // return the already exported project
        // TRICKY: we can only rely on this when all changes are commited to the repo
        if(outputZipFile.isFile() && commitSucceeded) {
            return outputZipFile.getAbsolutePath();
        }

        // export the project
        outputDir.mkdirs();
        for(int i = 0; i < p.numChapters(); i ++) {
            Chapter c = p.getChapter(i);
            if(c != null) {
                // check if any frames have been translated
                File chapterDir = new File(p.getRepositoryPath(), c.getId());
                if(!chapterDir.exists()) continue;
                String[] translatedFrames = chapterDir.list(new FilenameFilter() {
                    @Override
                    public boolean accept(File file, String s) {
                        return !s.equals("title") && !s.equals("reference");
                    }
                });
                if(translatedFrames.length == 0 && c.getTitleTranslation().getText().trim().isEmpty() && c.getReferenceTranslation().getText().trim().isEmpty()) continue;

                // compile translation
                File chapterFile = new File(outputDir, c.getId() + ".txt");
                chapterFile.createNewFile();
                PrintStream ps = new PrintStream(chapterFile);

                // language
                ps.print("//");
                ps.print(p.getSelectedTargetLanguage().getName());
                ps.println("//");
                ps.println();

                // project
                ps.print("//");
                ps.print(p.getId());
                ps.println("//");
                ps.println();

                // chapter title
//                if(!c.getTitleTranslation().getText().trim().isEmpty()) {
                ps.print("======");
                ps.print(c.getTitleTranslation().getText().trim());
                ps.println("======");
                ps.println();
//                }

                // frames
                for(int j = 0; j < c.numFrames(); j ++) {
                    Frame f = c.getFrame(j);
                    if(f != null && !f.getTranslation().getText().isEmpty()) {
                        // image
                        ps.print("{{");
                        // TODO: the api version and image dimensions should be placed in the user preferences
                        String apiVersion = "1";
                        // TODO: for now all images use the english versions
                        String languageCode = "en"; // eventually we should use: getSelectedTargetLanguage().getId()
                        ps.print(MainContext.getContext().getUserPreferences().getString(SettingsActivity.KEY_PREF_MEDIA_SERVER, MainContext.getContext().getResources().getString(R.string.pref_default_media_server))+"/"+p.getId()+"/jpg/"+apiVersion+"/"+languageCode+"/360px/"+p.getId()+"-"+languageCode+"-"+c.getId()+"-"+f.getId()+".jpg");
                        ps.println("}}");
                        ps.println();

                        // convert tags
                        String text = f.getTranslation().getText().trim();
                        Matcher matcher = pattern.matcher(text);
                        String convertedText = "";
                        int lastEnd = 0;
                        while(matcher.find()) {
                            if(matcher.start() > lastEnd) {
                                // add the last piece
                                convertedText += text.substring(lastEnd, matcher.start());
                            }
                            lastEnd = matcher.end();

                            // extract note
                            NoteSpan note = NoteSpan.getInstanceFromXML(matcher.group());
                            if(note.getNoteType() == NoteSpan.NoteType.Footnote) {
                                // iclude footnotes
                                convertedText += note.generateDokuWikiTag();
                            } else if(note.getNoteType() == NoteSpan.NoteType.UserNote) {
                                // skip user notes
                                convertedText += note.getSpanText();
                            }
                        }
                        if(lastEnd < text.length()) {
                            convertedText += text.substring(lastEnd, text.length());
                        }

                        // text
                        ps.println(convertedText);
                        ps.println();
                    }
                }

                // chapter reference
//                if(!c.getReferenceTranslation().getText().trim().isEmpty()) {
                ps.print("//");
                ps.print(c.getReferenceTranslation().getText().trim());
                ps.println("//");
//                }

                ps.close();
            }
        }

        // zip
        MainContext.getContext().zip(outputDir.getAbsolutePath(), outputZipFile.getAbsolutePath());
        // cleanup
        FileUtilities.deleteRecursive(outputDir);
        return outputZipFile.getAbsolutePath();
    }
}