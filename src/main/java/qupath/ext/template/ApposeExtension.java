package qupath.ext.template;

import javafx.beans.property.BooleanProperty;
import javafx.scene.control.MenuItem;
import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.objects.PathObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


/**
 * This is a demo to provide a template for creating a new QuPath extension.
 * <p>
 * It doesn't do much - it just shows how to add a menu item and a preference.
 * See the code and comments below for more info.
 * <p>
 * <b>Important!</b> For your extension to work in QuPath, you need to make sure the name &amp; package
 * of this class is consistent with the file
 * <pre>
 *     /resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension
 * </pre>
 */
public class ApposeExtension implements QuPathExtension, GitHubProject {
	
	private static final Logger logger = LoggerFactory.getLogger(ApposeExtension.class);

	/**
	 * Display name for your extension
	 */
	private static final String EXTENSION_NAME = "My Java extension";

	/**
	 * Short description, used under 'Extensions > Installed extensions'
	 */
	private static final String EXTENSION_DESCRIPTION = "This is just a demo to show how extensions work";

	/**
	 * QuPath version that the extension is designed to work with.
	 * This allows QuPath to inform the user if it seems to be incompatible.
	 */
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.4.0");

	/**
	 * Flag whether the extension is already installed (might not be needed... but we'll do it anyway)
	 */
	private boolean isInstalled = false;

	/**
	 * A 'persistent preference' - showing how to create a property that is stored whenever QuPath is closed
	 */
	private BooleanProperty enableExtensionProperty = PathPrefs.createPersistentPreference(
			"enableExtension", true);

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addPreference(qupath);
		addMenuItem(qupath);
	}

	/**
	 * Demo showing how to add a persistent preference to the QuPath preferences pane.
	 * @param qupath
	 */
	private void addPreference(QuPathGUI qupath) {
		qupath.getPreferencePane().addPropertyPreference(
				enableExtensionProperty,
				Boolean.class,
				"Enable my extension",
				EXTENSION_NAME,
				"Enable my extension");
	}

	/**
	 * Demo showing how a new command can be added to a QuPath menu.
	 * @param qupath
	 */
	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		MenuItem menuItem = new MenuItem("My menu item");
		menuItem.setOnAction(e -> {
			runPythonCode();
		});
		menuItem.disableProperty().bind(enableExtensionProperty.not());
		menu.getItems().add(menuItem);
	}

	private void runPythonCode() {
		String script = """

from math import log2
import leidenalg
import igraph
import sklearn.neighbors
from sknetwork.clustering import Louvain
import umap
import umap.plot

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib

df = pd.DataFrame.from_dict(measurements)
numerics = ['int16', 'int32', 'int64', 'float16', 'float32', 'float64']
fdf = df.select_dtypes(include=numerics)
fdf = fdf[fdf.columns.drop(list(fdf.filter(regex='Name')))]
fdf = fdf[fdf.columns.drop(list(fdf.filter(regex='Classification')))]
fdf = fdf[fdf.columns.drop(list(fdf.filter(like='Centroid')))]
fdf = fdf.loc[:, (fdf != fdf.iloc[0]).any()]
fdf = fdf.filter(like="mean")
# for column in fdf.columns:
#    fdf[column] = [log2(x+1) for x in fdf[column]]
normalized_fdf = (fdf - fdf.mean()) / fdf.std()

n_neighbors = 50
knn_adjacency = sklearn.neighbors.kneighbors_graph(
    normalized_fdf.values,
    n_neighbors = n_neighbors,
    mode = 'connectivity',
    n_jobs = 4)
sources, targets = knn_adjacency.nonzero()
edgelist = zip(sources.tolist(), targets.tolist())
G = igraph.Graph(edgelist)

leiden_partition = leidenalg.find_partition(G, leidenalg.ModularityVertexPartition, n_iterations=-1)
# part = leidenalg.find_partition(G, leidenalg.CPMVertexPartition, n_iterations=-1)
mapper = umap.UMAP(n_neighbors = n_neighbors, n_epochs = 250).fit(normalized_fdf.values)
filtered = normalized_fdf.filter(like="mean") # for plots

task.outputs["umap"] = mapper.transform(normalized_fdf.values)
task.outputs["labels"] = np.array(leiden_partition.membership)
""";

		Environment env = Appose.system();
//		Environment env = Appose.conda(new File("/home/alan/Documents/github/imaging/qupath-extension-unsupervised/clustering-conda-env.yml")).build();
		try (Service python = env.python()) {
			Map<String, Object> inputs = new HashMap<>();
			Set<String> measurementNames = new java.util.HashSet<>();
			var objects = QPEx.getDetectionObjects();
			measurementNames.addAll(objects.iterator().next().getMeasurements().keySet());
			for (String key: measurementNames) {
				double[] values = objects.stream().mapToDouble(o -> o.getMeasurements().get(key).doubleValue()).toArray();
				inputs.put(key, values);
			}
			Service.Task task = python.task(script, Map.of("measurements", inputs));
			task.listen(event -> {
				switch (event.responseType) {
					case UPDATE:
						System.out.println("Progress: " + task.current + "/" + task.maximum);
						break;
					case COMPLETION:
						System.out.println("Task complete. Printing the output now...");
						System.out.println(task.outputs.get("umap"));
						System.out.println(task.outputs.get("labels"));
						break;
					case CANCELATION:
						System.out.println("Task canceled");
						break;
					case FAILURE:
						System.out.println("Task failed: " + task.error);
						break;
				}
			});
			task.start();
			Thread.sleep(1000);
			if (!task.status.isFinished()) {
				// Task is taking too long; request a cancelation.
				task.cancel();
			}
			task.waitFor();
		} catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}
	
	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}

	@Override
	public GitHubRepo getRepository() {
		return GitHubProject.GitHubRepo.create("Appose extension", "qupath", "qupath-extension-appose");
	}
}
