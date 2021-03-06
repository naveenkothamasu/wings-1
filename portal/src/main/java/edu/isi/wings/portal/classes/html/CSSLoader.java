package edu.isi.wings.portal.classes.html;

import java.io.PrintWriter;

public class CSSLoader {
	static String theme = "classic";
	static String[] site_css = { "css/menu.css", "css/app.css" };
	static String[] extjs_css = { "lib/extjs/resources/ext-theme-"+theme+"/ext-theme-"+theme+"-all.css" };

	public static void loadDataViewer(PrintWriter out, String path) {
		showCssTags(out, path, site_css);
		showCssTags(out, path, extjs_css);
	}

	public static void loadComponentViewer(PrintWriter out, String path) {
		showCssTags(out, path, site_css);
		showCssTags(out, path, extjs_css);
	}

	public static void loadDomainViewer(PrintWriter out, String path) {
		showCssTags(out, path, site_css);
		showCssTags(out, path, extjs_css);
	}

	public static void loadRunViewer(PrintWriter out, String path) {
		showCssTags(out, path, site_css);
		showCssTags(out, path, extjs_css);
	}

	public static void loadTemplateViewer(PrintWriter out, String path) {
		showCssTags(out, path, site_css);
		showCssTags(out, path, extjs_css);
	}

	private static void showCssTags(PrintWriter out, String path, String[] css) {
		for (String href : css) {
			out.println("<link rel=\"stylesheet\" href=\"" + path + "/" + href + "\"/>");
		}
	}
}