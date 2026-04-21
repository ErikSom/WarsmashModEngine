package com.etheller.warsmash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.etheller.warsmash.datasources.CascDataSourceDescriptor;
import com.etheller.warsmash.datasources.CompoundDataSource;
import com.etheller.warsmash.datasources.CompoundDataSourceDescriptor;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.datasources.DataSourceDescriptor;
import com.etheller.warsmash.datasources.FolderDataSourceDescriptor;
import com.etheller.warsmash.datasources.MpqDataSourceDescriptor;
import com.etheller.warsmash.datasources.SubdirDataSource;
import com.etheller.warsmash.units.DataTable;
import com.etheller.warsmash.units.Element;

/**
 * Lives separate from {@link WarsmashGdxMapScreen}/{@link WarsmashGdxMenuScreen}
 * so web builds can pull in just the DataSource assembly logic without
 * dragging in map-screen and menu-screen classes that transitively reference
 * AWT / TGA / image decoding.
 *
 * <p>{@link #overrideDataSource} is a platform-level escape hatch: if
 * non-null, {@link #parseDataSources(DataTable)} returns it verbatim and
 * skips the INI's {@code [DataSources]} assembly.
 */
public final class DataSourceAssembly {
	public static DataSource overrideDataSource;

	private DataSourceAssembly() {
	}

	public static DataSource parseDataSources(final DataTable warsmashIni) {
		if (overrideDataSource != null) {
			return overrideDataSource;
		}
		final Element dataSourcesConfig = warsmashIni.get("DataSources");
		final List<DataSourceDescriptor> dataSourcesList = new ArrayList<>();
		final List<String> allCascPrefixes = new ArrayList<>();
		for (int i = 0; i < dataSourcesConfig.size(); i++) {
			final String type = dataSourcesConfig.getField("Type" + (i < 10 ? "0" : "") + i);
			final String path = dataSourcesConfig.getField("Path" + (i < 10 ? "0" : "") + i);
			switch (type) {
			case "Folder":
				dataSourcesList.add(new FolderDataSourceDescriptor(path));
				break;
			case "MPQ":
				dataSourcesList.add(new MpqDataSourceDescriptor(path));
				break;
			case "CASC":
				final String prefixes = dataSourcesConfig.getField("Prefixes" + (i < 10 ? "0" : "") + i);
				final List<String> parsedPrefixes = Arrays.asList(prefixes.split(","));
				allCascPrefixes.addAll(parsedPrefixes);
				dataSourcesList.add(new CascDataSourceDescriptor(path, parsedPrefixes));
				break;
			case "":
				continue;
			default:
				throw new RuntimeException("Unknown data source type: " + type);
			}
		}
		final DataSource baseCompoundDataSource = new CompoundDataSourceDescriptor(dataSourcesList).createDataSource();
		final List<DataSource> subdirDataSourcesList = new ArrayList<>();
		for (final String prefix : allCascPrefixes) {
			subdirDataSourcesList.add(new SubdirDataSource(baseCompoundDataSource, prefix + "\\"));
		}
		subdirDataSourcesList.add(baseCompoundDataSource);
		return new CompoundDataSource(subdirDataSourcesList);
	}
}
