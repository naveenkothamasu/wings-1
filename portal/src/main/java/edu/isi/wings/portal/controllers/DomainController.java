package edu.isi.wings.portal.controllers;

import java.io.File;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.plist.PropertyListConfiguration;

import com.google.gson.Gson;

import edu.isi.wings.portal.classes.Config;
import edu.isi.wings.portal.classes.JsonHandler;
import edu.isi.wings.portal.classes.domains.Domain;
import edu.isi.wings.portal.classes.domains.DomainInfo;
import edu.isi.wings.portal.classes.html.CSSLoader;
import edu.isi.wings.portal.classes.html.JSLoader;

public class DomainController {
	private int guid;
	private String uploadScript;
	private Config config;
	private Gson json;
	
	private Domain domain;
	private HashMap<String, DomainInfo> user_domains;
	private String defaultDomainName = "blank";
	
	String userdir;
	String userConfigFile;

	public DomainController(int guid, Config config) {
		this.guid = guid;
		this.config = config;
		this.json = JsonHandler.createPrettyGson();
		this.uploadScript = config.getContextRootPath() + "/upload";
		this.user_domains = new HashMap<String, DomainInfo>();
		this.userConfigFile = config.getUserDir() + "/user.properties";
		
		this.initializeDomainList();
	}

	public void show(PrintWriter out) {
		// Get Hierarchy
		try {
			String list = this.getDomainsListJSON();
			//System.out.println(list);
			
			out.println("<html>");
			out.println("<head>");
			JSLoader.setContextRoot(out, config.getContextRootPath());
			CSSLoader.loadDomainViewer(out, config.getContextRootPath());
			JSLoader.loadDomainViewer(out, config.getContextRootPath());
			out.println("</head>");
	
			out.println("<script>");
			out.println("var domainViewer_" + guid + ";");
			out.println("Ext.onReady(function() {"
					+ "domainViewer_" + guid + " = new DomainViewer("
						+ "'" + guid + "', " 
						+ list + ", " 
						+ "'" + config.getScriptPath() + "', "
						+ "'" + this.uploadScript + "'" 
						+ ");\n"
						+ "domainViewer_" + guid + ".initialize();\n"
					+ "});");
			out.println("</script>");
			out.println("</html>");
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	public String getDomainsListJSON() {
		return "{ list: " + json.toJson(user_domains.values()) 
				 + ", selected: "+ json.toJson(domain.getDomainName()) + "}";
	}
	
	public String getDomainJSON (String domain) {
		DomainInfo dominfo = this.user_domains.get(domain);
		Domain dom = new Domain(dominfo);
		return json.toJson(dom);
	}
	
	public Domain getUserDomain() {
		return this.domain;
	}
	
	public String importDomain(String domain, String location) {
		File f = new File(location);
		if(!f.exists() || !f.isDirectory())
			return null;
		
		Domain dom = null;
		File oldf = new File(f.getAbsolutePath() + File.separator + "wings.properties");
		if(oldf.exists())
			dom = Domain.importLegacyDomain(domain, this.config, location);
		
		File newf = new File(f.getAbsolutePath() + File.separator + "domain.properties");
		if(newf.exists())
			dom = Domain.importDomain(domain, this.config, location);
		
		DomainInfo dominfo = new DomainInfo(dom);
		this.user_domains.put(dom.getDomainName(), dominfo);
		this.saveUserConfig(this.userConfigFile);
		return json.toJson(dominfo);
	}
	
	public boolean selectDomain(String domain) {
		DomainInfo dominfo = this.user_domains.get(domain);
		this.domain = new Domain(dominfo);
		if(this.saveUserConfig(this.userConfigFile))
			return true;
		return false;
	}
	
	public boolean deleteDomain(String domain) {
		DomainInfo dominfo = this.user_domains.get(domain);
		if(dominfo != null) {
			this.user_domains.remove(domain);
			Domain dom = new Domain(dominfo);
			if(!Domain.deleteDomain(dom, config, true))
				return false;
		}
		if(this.saveUserConfig(this.userConfigFile))
			return true;
		return false;
	}
	
	@SuppressWarnings("unchecked")
	private void initializeDomainList() {
		PropertyListConfiguration config = this.getUserConfiguration();
		List<SubnodeConfiguration> domnodes = config.configurationsAt("user.domains.domain");
		String domname = config.getString("user.domain");
		for (SubnodeConfiguration domnode : domnodes) {
			String domurl = domnode.getString("url");
			Boolean isLegacy = domnode.getBoolean("legacy", false);
			String dname = domnode.getString("name");
			if(dname.equals(domname)) 
				this.domain = new Domain(dname, domnode.getString("dir"), domurl, isLegacy);
			DomainInfo dominfo = new DomainInfo(dname, domnode.getString("dir"), domurl, isLegacy);
			this.user_domains.put(dominfo.getName(), dominfo);
		}
	}
	
	private PropertyListConfiguration getUserConfiguration() {
		this.userdir = this.config.getUserDir();
		this.userConfigFile = userdir + "/user.properties";
		// Create userConfigFile if it doesn't exist
		File cfile = new File(userConfigFile);
		if(!cfile.exists()) {
			if(!cfile.getParentFile().exists() && !cfile.getParentFile().mkdirs()) {
				System.err.println("Cannot create config file directory : "+cfile.getParent());
				return null;
			}
			createDefaultUserConfig(userConfigFile);
		}
		// Load properties from configFile
		PropertyListConfiguration config = new PropertyListConfiguration();
		try {
			config.load(userConfigFile);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return config;
	}
	
	private void createDefaultUserConfig(String configFile) {
		this.domain = Domain.createDefaultDomain(this.defaultDomainName, config.getUserDir(), config.getUserUrl());
		DomainInfo dominfo = new DomainInfo(this.domain);
		this.user_domains.put(this.domain.getDomainName(), dominfo);
		this.saveUserConfig(configFile);
	}
	
	private boolean saveUserConfig(String file) {
		PropertyListConfiguration config = new PropertyListConfiguration();
		config.addProperty("user.domain", this.domain.getDomainName());
		for (String domname : this.user_domains.keySet()) {
			DomainInfo dom = this.user_domains.get(domname);
			config.addProperty("user.domains.domain(-1).name", dom.getName());
			config.addProperty("user.domains.domain.dir", dom.getDirectory());
			if(dom.isLegacy())
				config.addProperty("user.domains.domain.legacy", dom.isLegacy());
			else
				config.addProperty("user.domains.domain.url", dom.getUrl());
		}
		try {
			config.save(file);
			return true;
		} catch (ConfigurationException e) {
			e.printStackTrace();
			return false;
		}
	}
}
