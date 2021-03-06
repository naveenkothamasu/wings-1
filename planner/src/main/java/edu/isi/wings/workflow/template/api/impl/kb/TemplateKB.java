package edu.isi.wings.workflow.template.api.impl.kb;

import edu.isi.wings.common.SerializableObjectCloner;
import edu.isi.wings.common.URIEntity;
import edu.isi.wings.common.UuidGen;
import edu.isi.wings.common.kb.KBUtils;
import edu.isi.wings.ontapi.KBAPI;
import edu.isi.wings.ontapi.KBObject;
import edu.isi.wings.ontapi.OntFactory;
import edu.isi.wings.ontapi.OntSpec;
import edu.isi.wings.workflow.template.api.ConstraintEngine;
import edu.isi.wings.workflow.template.api.Template;
import edu.isi.wings.workflow.template.classes.Link;
import edu.isi.wings.workflow.template.classes.Metadata;
import edu.isi.wings.workflow.template.classes.Node;
import edu.isi.wings.workflow.template.classes.Port;
import edu.isi.wings.workflow.template.classes.Role;
import edu.isi.wings.workflow.template.classes.Rules;
import edu.isi.wings.workflow.template.classes.sets.Binding;
import edu.isi.wings.workflow.template.classes.sets.ComponentSetCreationRule;
import edu.isi.wings.workflow.template.classes.sets.PortSetCreationRule;
import edu.isi.wings.workflow.template.classes.sets.SetExpression;
import edu.isi.wings.workflow.template.classes.sets.ValueBinding;
import edu.isi.wings.workflow.template.classes.sets.WingsSet;
import edu.isi.wings.workflow.template.classes.sets.SetCreationRule.SetType;
import edu.isi.wings.workflow.template.classes.sets.SetExpression.SetOperator;
import edu.isi.wings.workflow.template.classes.variables.ComponentVariable;
import edu.isi.wings.workflow.template.classes.variables.DataVariable;
import edu.isi.wings.workflow.template.classes.variables.ParameterVariable;
import edu.isi.wings.workflow.template.classes.variables.Variable;
import edu.isi.wings.workflow.template.util.LayoutHelper;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import javax.xml.datatype.DatatypeFactory;

import com.hp.hpl.jena.datatypes.xsd.XSDDateTime;

public class TemplateKB extends URIEntity implements Template {
	private static final long serialVersionUID = 1L;
	private static final int latestVersion = 3;

	int version = 0;

	protected transient OntFactory ontologyFactory;
	protected transient KBAPI kb;

	protected String onturl;
	protected String wflowns;

	
	private Node[] Nodes = new Node[0];
	private Link[] Links = new Link[0];
	private Variable[] Variables = new Variable[0];

	// Map of variable ids to template roles
	private HashMap<String, Role> inputRoles = new HashMap<String, Role>();
	private HashMap<String, Role> outputRoles = new HashMap<String, Role>();

	private transient HashMap<String, KBObject> propertyObjMap;
	private transient HashMap<String, KBObject> conceptObjMap;
	private transient ConstraintEngine constraintEngine;

	private HashMap<String, Template> subtemplates = new HashMap<String, Template>();

	transient private Template createdFrom;
	transient private Template parent;

	protected Metadata metadata;
	protected Rules rules;
	
	protected Properties props;

	public TemplateKB(String id) {
		super(id);
	}

	// This constructor loads in a template from a url
	public TemplateKB(Properties props, String id) {
		super(id);
		this.props = props;
		this.initVariables(props);
		this.initializeKB(props);
		this.readTemplate();
	}

	// This constructor creates a new blank plain template
	public TemplateKB(Properties props, String id, boolean createNew) {
		super(id);
		this.props = props;
		this.initVariables(props);
		this.initializeKB(props, false);
		kb.createObjectOfClass(this.getID(), kb.getConcept(wflowns + "WorkflowTemplate"));
		this.metadata = new Metadata();
		this.rules = new Rules();
	}
	
	protected void initVariables(Properties props) {
		this.onturl = props.getProperty("ont.workflow.url");
		this.wflowns = this.onturl + "#";
		propertyObjMap = new HashMap<String, KBObject>();
		conceptObjMap = new HashMap<String, KBObject>();
	}
	
	protected void initializeKB(Properties props) {
		this.initializeKB(props, true);
	}
	
	private void initializeKB(Properties props, boolean load_template) {
		String tdbRepository = props.getProperty("tdb.repository.dir");
		if (tdbRepository == null) {
			this.ontologyFactory = new OntFactory(OntFactory.JENA);
		} else {
			this.ontologyFactory = new OntFactory(OntFactory.JENA, tdbRepository);
		}
		KBUtils.createLocationMappings(props, this.ontologyFactory);
		try {
			// Using a PLAIN kb as we don't need much inference here
			if(load_template)
				kb = this.ontologyFactory.getKB(this.getURL(), OntSpec.PLAIN);
			else
				kb = this.ontologyFactory.getKB(OntSpec.PLAIN);
			kb.importFrom(ontologyFactory.getKB(this.onturl, OntSpec.PLAIN, true));
			this.constraintEngine = new ConstraintEngineKB(kb, this.wflowns);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	
	/**
	 * Make a copy of another template
	 * @param t
	 *            the template to copy from
	 */
	public TemplateKB(TemplateKB t) {
		super(t.getID());
		this.props = t.props;
		this.initVariables(this.props);
		
		Variables = new Variable[0];
		Links = new Link[0];
		Nodes = new Node[0];
		copyBookkeepingInfo(t);

		// copy rules
		this.rules = new Rules();
		if (t.rules != null)
			this.rules.setRulesText(t.rules.getRulesText());

		// copy metadata
		this.metadata = new Metadata();
		if (t.metadata != null) {
			this.metadata.documentation = t.metadata.documentation;
			this.metadata.contributors = new ArrayList<String>(t.metadata.contributors);
		}
		this.ontologyFactory = t.ontologyFactory;
		this.kb = t.kb;
		
		this.constraintEngine = new ConstraintEngineKB((ConstraintEngineKB) t.getConstraintEngine());
	}

	private void copyBookkeepingInfo(TemplateKB t) {
		this.setID(t.getID());;
		this.wflowns = t.wflowns;
	}

	public String getInternalRepresentation() {
		// return this.kb.toN3();
		return this.kb.toRdf(true);
	}
	
	public void setName(String name) {
		this.setID(this.getNamespace()+name);
	}

	private void cacheConceptsAndProperties() {
		for (KBObject obj : kb.getAllClasses()) {
			if(obj != null)
				conceptObjMap.put(obj.getName(), obj);
		}
		for (KBObject obj : kb.getAllObjectProperties()) {
			if(obj != null)
				propertyObjMap.put(obj.getName(), obj);
		}
		for (KBObject obj : kb.getAllDatatypeProperties()) {
			if(obj != null)
				propertyObjMap.put(obj.getName(), obj);
		}
		// Legacy ontologies don't have some concepts & properties. Add them in here
		if(!propertyObjMap.containsKey("hasRoleID"))
			propertyObjMap.put("hasRoleID", kb.createDatatypeProperty(this.wflowns+"hasRoleID"));
		if(!propertyObjMap.containsKey("hasMetadata"))
			propertyObjMap.put("hasMetadata", kb.createDatatypeProperty(this.wflowns+"hasMetadata"));
		if(!conceptObjMap.containsKey("ReduceDimensionality"))
			conceptObjMap.put("ReduceDimensionality", kb.createClass(this.wflowns+"ReduceDimensionality"));
		if(!conceptObjMap.containsKey("Shift"))
			conceptObjMap.put("Shift", kb.createClass(this.wflowns+"Shift"));
	}

	private Node readNodeFromKB(KBObject obj) {
		if (obj == null)
			return null;

		KBObject compObj = kb.getPropertyValue(obj, propertyObjMap.get("hasComponent"));
		KBObject wObj = kb.getPropertyValue(obj, propertyObjMap.get("hasWorkflow"));

		Node n = new Node(obj.getID());

		if (compObj != null) {
			ComponentVariable comp = new ComponentVariable(compObj.getID());

			KBObject cval = kb.getDatatypePropertyValue(compObj, propertyObjMap.get("isConcrete"));
			if (cval != null && cval.getValue() != null && (Boolean) cval.getValue())
				comp.setConcrete(true);

			KBObject cbinding = kb.getPropertyValue(compObj,
					propertyObjMap.get("hasComponentBinding"));
			if (cbinding != null) {
				comp.setBinding(readBindingObjectFromKB(kb, cbinding));
			}
			else {
				// Legacy compatibility (where component binding is provided as a type)
				ArrayList<KBObject> compClses = kb.getPropertyValues(compObj,
						kb.getProperty(KBUtils.RDF + "type"));
				for (KBObject compCls : compClses) {
					if (!compCls.getNamespace().equals(this.wflowns))
						comp.setBinding(new Binding(compCls.getID()));
				}
			}
			n.setComponentVariable(comp);
		}

		else if (wObj != null) {
			Template t = readTemplate(wObj);
			ComponentVariable comp = new ComponentVariable(t);
			n.setComponentVariable(comp);
		}

		return n;
	}

	private Node getNode(KBObject obj, Node[] nodes) {
		if (obj == null) {
			return null;
		}
		for (Node element : nodes) {
			if (element.getID().equals(obj.getID())) {
				return element;
			}
		}
		return null;
	}

	protected void readTemplate() {
		cacheConceptsAndProperties();

		ArrayList<KBObject> tobjs = kb.getInstancesOfClass(conceptObjMap.get("WorkflowTemplate"),
				false);

		// Read all the templates into subtemplates
		for (KBObject tobj : tobjs) {
			readTemplate(tobj);
		}

		// Get the root template (no parent). Copy over its links, nodes, and
		// variables
		String rootid = null;
		for (String id : subtemplates.keySet()) {
			Template t = subtemplates.get(id);
			if (t.getParent() == null) {
				Links = t.getLinks();
				Variables = t.getVariables();
				Nodes = t.getNodes();
				metadata = t.getMetadata();
				rules = t.getRules();
				inputRoles = t.getInputRoles();
				outputRoles = t.getOutputRoles();
				rootid = id;
				break;
			}
		}

		// Remove the root subtemplate
		subtemplates.remove(rootid);
	}

	/**
	 * @param templateObj
	 * @return
	 */
	private Template readTemplate(KBObject templateObj) {
		if (subtemplates.containsKey(templateObj.getID()))
			return subtemplates.get(templateObj.getID());

		TemplateKB t = new TemplateKB(templateObj.getID());
		t.copyBookkeepingInfo(this);

		// Create a new constraint engine
		t.constraintEngine = new ConstraintEngineKB(kb, wflowns);

		subtemplates.put(templateObj.getID(), t);

		HashMap<String, KBObject> pmap = propertyObjMap;
		HashMap<String, KBObject> cmap = conceptObjMap;

		KBObject verobj = kb.getPropertyValue(templateObj, pmap.get("hasVersion"));
		if (verobj != null) {
			if(verobj.getValue().getClass() == Float.class)
				t.version = ((Float)verobj.getValue()).intValue();
			else if(verobj.getValue().getClass() == Integer.class)
				t.version = (Integer) verobj.getValue();
		}

		// ns = (String) kb.getPrefixNamespaceMap().get("");

		ArrayList<KBObject> linkObjs = kb.getPropertyValues(templateObj, pmap.get("hasLink"));
		ArrayList<KBObject> nodeObjs = kb.getPropertyValues(templateObj, pmap.get("hasNode"));

		Node[] nodes = new Node[nodeObjs.size()];
		Link[] links = new Link[linkObjs.size()];

		HashMap<String, Variable> varMap = new HashMap<String, Variable>();
		// Variables = new Variable[links.size()];

		int i = 0;
		for (KBObject nodeObj : nodeObjs) {
			Node node = readNodeFromKB(nodeObj);
			if (node.getComponentVariable().getTemplate() != null) {
				node.getComponentVariable().getTemplate().setParent(t);
			}
			nodes[i] = node;
			i++;
			String comment = kb.getComment(nodeObj);
			if (comment != null)
				node.setComment(comment);

			/* New Version Queries */
			if (t.version > 0) {
				ArrayList<KBObject> ports = kb
						.getPropertyValues(nodeObj, pmap.get("hasInputPort"));
				for (KBObject port : ports) {
					node.addInputPort(readPortFromKB(port));
				}
				ports = kb.getPropertyValues(nodeObj, pmap.get("hasOutputPort"));
				for (KBObject port : ports) {
					node.addOutputPort(readPortFromKB(port));
				}

				KBObject cruleobj = kb.getPropertyValue(nodeObj,
						pmap.get("hasComponentSetCreationRule"));
				KBObject pruleobj = kb.getPropertyValue(nodeObj,
						pmap.get("hasPortSetCreationRule"));

				ComponentSetCreationRule crule = null;
				PortSetCreationRule prule = null;

				if (cruleobj != null) {
					// KBObject compobj = kb.getPropertyValue(pruleobj,
					// pmap.get("createSetsOn"));

					KBObject stype = kb
							.getPropertyValue(cruleobj, pmap.get("createComponentSets"));
					KBObject wtype = kb.getPropertyValue(cruleobj, pmap.get("createWorkflowSets"));
					if (stype != null && (Boolean) stype.getValue()) {
						crule = new ComponentSetCreationRule(SetType.STYPE);
					}
					if (wtype != null && (Boolean) wtype.getValue()) {
						crule = new ComponentSetCreationRule(SetType.WTYPE);
					}
				}
				if (pruleobj != null) {
					KBObject exprobj = kb.getPropertyValue(pruleobj, pmap.get("createSetsOn"));
					SetExpression expr = readSetExpressionFromKB(kb, exprobj, node);

					KBObject stype = kb
							.getPropertyValue(pruleobj, pmap.get("createComponentSets"));
					KBObject wtype = kb.getPropertyValue(pruleobj, pmap.get("createWorkflowSets"));
					if (stype != null && (Boolean) stype.getValue()) {
						prule = new PortSetCreationRule(SetType.STYPE, expr);
					}
					if (wtype != null && (Boolean) wtype.getValue()) {
						prule = new PortSetCreationRule(SetType.WTYPE, expr);
					}
				}
				if (crule != null)
					node.addComponentSetRule(crule);
				if (prule != null)
					node.addPortSetRule(prule);
			} else {
				// Default WType for component sets
				node.addComponentSetRule(new ComponentSetCreationRule(SetType.WTYPE));

				// Default SType for data sets
				SetExpression expr = new SetExpression(SetOperator.XPRODUCT);
				node.addPortSetRule(new PortSetCreationRule(SetType.WTYPE, expr));
			}
		}

		i = 0;
		for (KBObject linkObj : linkObjs) {
			Node fromNode = getNode(kb.getPropertyValue(linkObj, pmap.get("hasOriginNode")), nodes);
			Node toNode = getNode(kb.getPropertyValue(linkObj, pmap.get("hasDestinationNode")),
					nodes);

			Port fromPort = null;
			Port toPort = null;

			if (t.version > 0) {
				KBObject fromPortObj = kb.getPropertyValue(linkObj, pmap.get("hasOriginPort"));
				KBObject toPortObj = kb.getPropertyValue(linkObj, pmap.get("hasDestinationPort"));

				if (fromNode != null && fromPortObj != null) {
					fromPort = fromNode.findOutputPort(fromPortObj.getID());
					if (fromPort == null) {
						fromPort = readPortFromKB(fromPortObj);
						fromNode.addOutputPort(fromPort);
					}
				}

				if (toNode != null && toPortObj != null) {
					toPort = toNode.findInputPort(toPortObj.getID());
					if (toPort == null) {
						toPort = readPortFromKB(toPortObj);
						toNode.addInputPort(toPort);
					}
				}
			} else {
				KBObject fromParamObj = kb.getPropertyValue(linkObj,
						pmap.get("hasOriginParameter"));
				KBObject toParamObj = kb.getPropertyValue(linkObj,
						pmap.get("hasDestinationParameter"));

				if (fromNode != null && fromParamObj != null) {
					int suff = fromNode.getOutputPorts().size();
					fromPort = new Port(linkObj.getNamespace() + "op" + suff);
					fromPort.setRole(new Role(fromParamObj.getID()));
					fromNode.addOutputPort(fromPort);
				}

				if (toNode != null && toParamObj != null) {
					int suff = toNode.getInputPorts().size();
					toPort = new Port(linkObj.getNamespace() + "ip" + suff);
					toPort.setRole(new Role(toParamObj.getID()));
					toNode.addInputPort(toPort);

					toNode.getPortSetRule().getSetExpression()
							.add(new SetExpression(SetOperator.XPRODUCT, toPort));
				}
			}
			if(linkObj.getID() == null) {
			    
			}
			String lid = linkObj.getID();
			if(lid == null) 
			    lid = this.createLinkId(fromPort, toPort);
			links[i] = new Link(lid, fromNode, toNode, fromPort, toPort);

			KBObject varObj = kb.getPropertyValue(linkObj, pmap.get("hasVariable"));

			Variable var = varMap.get(varObj.getID());
			if (var == null) {
				if (kb.isA(varObj, cmap.get("DataVariable"))) {
					var = new DataVariable(varObj.getID());
					KBObject dsBinding = kb.getPropertyValue(varObj, pmap.get("hasDataBinding"));
					if (dsBinding != null) {
						var.setBinding(readBindingObjectFromKB(kb, dsBinding));
					}
					varMap.put(varObj.getID(), var);
				} else if (kb.isA(varObj, cmap.get("ParameterVariable"))) {
					var = new ParameterVariable(varObj.getID());
					KBObject paramValue = kb.getDatatypePropertyValue(varObj,
							pmap.get("hasParameterValue"));
					if (paramValue != null && paramValue.getValue() != null) {
						var.setBinding(readValueBindingObjectFromKB(kb, paramValue));
					}
					varMap.put(varObj.getID(), var);
				}
			}
			if (var != null) {
				links[i].setVariable(var);
				String comment = kb.getComment(varObj);
				if (comment != null)
					var.setComment(comment);
			}
			i++;
		}

		t.Links = links;
		t.Nodes = nodes;
		t.Variables = varMap.values().toArray(new Variable[0]);

		readTemplateRolesFromKB(this.kb, t, templateObj);
		t.autoUpdateTemplateRoles();

		t.fillInDefaultSetCreationRules();

		t.metadata = readMetadata(this.kb, templateObj);
		t.rules = readRules(this.kb, templateObj);

		return t;
	}

	private void readTemplateRolesFromKB(KBAPI kb, Template t, KBObject templateObj) {
		ArrayList<KBObject> iroleObjs = kb.getPropertyValues(templateObj,
				propertyObjMap.get("hasInputRole"));
		ArrayList<KBObject> oroleObjs = kb.getPropertyValues(templateObj,
				propertyObjMap.get("hasOutputRole"));

		for (KBObject iroleObj : iroleObjs) {
			Role r = new Role(iroleObj.getID());
			r.setRoleId(iroleObj.getName());
			KBObject varObj = kb.getPropertyValue(iroleObj, propertyObjMap.get("mapsToVariable"));
			KBObject dimObj = kb.getDatatypePropertyValue(iroleObj,
					propertyObjMap.get("hasDimensionality"));
			KBObject idObj = kb.getDatatypePropertyValue(iroleObj,
					propertyObjMap.get("hasRoleID"));
			if (varObj == null) {
				System.err.println("Warning: Role " + iroleObj + " not mapped to any variable");
				continue;
			}
			if (dimObj != null && dimObj.getValue() != null) {
				r.setDimensionality((Integer) dimObj.getValue());
			}
			if (idObj != null && idObj.getValue() != null) {
				r.setRoleId((String) idObj.getValue());
			}
			t.addInputRole(varObj.getID(), r);
		}
		for (KBObject oroleObj : oroleObjs) {
			Role r = new Role(oroleObj.getID());
			r.setRoleId(oroleObj.getName());
			KBObject varObj = kb.getPropertyValue(oroleObj, propertyObjMap.get("mapsToVariable"));
			KBObject dimObj = kb.getDatatypePropertyValue(oroleObj,
					propertyObjMap.get("hasDimensionality"));
			KBObject idObj = kb.getDatatypePropertyValue(oroleObj,
					propertyObjMap.get("hasRoleID"));
			if (varObj == null) {
				System.err.println("Warning: Role " + oroleObj + " not mapped to any variable");
				continue;
			}
			if (dimObj != null && dimObj.getValue() != null) {
				r.setDimensionality((Integer) dimObj.getValue());
			}
			if (idObj != null && idObj.getValue() != null) {
				r.setRoleId((String) idObj.getValue());
			}
			t.addOutputRole(varObj.getID(), r);
		}
	}

	// TODO: On Deletion of a variable:
	// - Delete the Port too
	// - Delete the port from the PortSetExpression
	// - If it is an input/output variable, then delete the template
	// Input/Output role too

	public void autoUpdateTemplateRoles() {
		// Check if there are any input and output variables that 
		// dont have roles.
		// Assign them roles if they dont exist

		// Also check if there are any incorrect roles that have been assigned

		HashSet<String> rolevars = new HashSet<String>();
		ArrayList<String> ivars = new ArrayList<String>();
		ArrayList<String> ovars = new ArrayList<String>();
		for (Variable v : getInputVariables())
			ivars.add(v.getID());
		for (Variable v : getOutputVariables())
			ovars.add(v.getID());

		ArrayList<String> irolevars = new ArrayList<String>(inputRoles.keySet());
		ArrayList<String> orolevars = new ArrayList<String>(outputRoles.keySet());

		for (String irolevar : irolevars) {
			if (!ivars.contains(irolevar)) {
				inputRoles.remove(irolevar);
				continue;
			}
			else
				rolevars.add(irolevar);
		}
		for (String orolevar : orolevars) {
			if (!ivars.contains(orolevar)) {
				outputRoles.remove(orolevar);
				continue;
			}
			else
				rolevars.add(orolevar);
		}

		for (Variable v : getInputVariables()) {
			if (!rolevars.contains(v.getID())) {
				Role r = new Role(v.getID() + "_irole");
				r.setRoleId(v.getName());
				addInputRole(v.getID(), r);
			}
		}
		for (Variable v : getOutputVariables()) {
			if (!rolevars.contains(v.getID())) {
				Role r = new Role(v.getID() + "_orole");
				r.setRoleId(v.getName());
				addOutputRole(v.getID(), r);
			}
		}
	}

	// - In addition to checking whether the rule doesn't exist.. check that all
	// ports exist
	// - if a port doesn't exist in the set expression, then add it

	public void fillInDefaultSetCreationRules() {
		// Add default set creation rules if they are not present
		for (Node n : Nodes) {
			this.addDefaultSetCreationRulesForNode(n);
		}
	}

	private void addDefaultSetCreationRulesForNode(Node n) {
		ComponentSetCreationRule crule = n.getComponentSetRule();
		if (crule == null) {
			// Default WType for component sets
			n.addComponentSetRule(new ComponentSetCreationRule(SetType.WTYPE));
		}

		PortSetCreationRule prule = n.getPortSetRule();
		if (prule == null) {
			// Default SType for data sets
			SetExpression expr = new SetExpression(SetOperator.XPRODUCT);
			prule = new PortSetCreationRule(SetType.WTYPE, expr);
		}

		HashSet<Port> ruleports = new HashSet<Port>();
		ArrayList<SetExpression> exprs = new ArrayList<SetExpression>();
		exprs.add(prule.getSetExpression());
		while (!exprs.isEmpty()) {
			SetExpression ex = exprs.remove(0);
			if (ex.isSet())
				exprs.addAll(ex);
			else
				ruleports.add(ex.getPort());
		}

		// Default X-Product of all ports that are not defined in the prule
		// FIXME: I wonder if this should be for datavariable ports only ?
		Link[] links = this.getInputLinks(n);
		for (Link l : links) {
			Port p = l.getDestinationPort();
			if (l.getVariable() != null && !ruleports.contains(p))
				prule.getSetExpression().add(new SetExpression(SetOperator.XPRODUCT, p));
		}

		n.addPortSetRule(prule);
	}

	public Role getInputRoleForVariable(Variable v) {
		return this.inputRoles.get(v.getID());
	}

	public Role getOutputRoleForVariable(Variable v) {
		return this.outputRoles.get(v.getID());
	}

	protected Metadata readMetadata(KBAPI kb, KBObject tobj) {
		this.cacheConceptsAndProperties();

		Metadata m = new Metadata();
		KBObject mobj = kb.getPropertyValue(tobj, this.propertyObjMap.get("hasMetadata"));
		if (mobj == null)
			return m;

		// Fetch metadata
		KBObject val = kb.getPropertyValue(mobj, propertyObjMap.get("lastUpdateTime"));
		if (val != null) {
			m.setLastUpdateTime((XSDDateTime) val.getValue());
		}

		val = kb.getPropertyValue(mobj, propertyObjMap.get("hasDocumentation"));
		if (val != null)
			m.documentation = (String) val.getValue();

		ArrayList<KBObject> crs = kb.getPropertyValues(mobj, propertyObjMap.get("hasContributor"));
		for (KBObject cr : crs) {
			if (!m.contributors.contains(cr.getValue()))
				m.contributors.add((String) cr.getValue());
		}

		ArrayList<KBObject> templates = kb.getPropertyValues(mobj,
				propertyObjMap.get("createdFrom"));
		for (KBObject tmp : templates) {
			if (!m.createdFrom.contains(tmp.getValue()))
				m.createdFrom.add((String) tmp.getValue());
		}
		return m;
	}

	protected Rules readRules(KBAPI kb, KBObject tobj) {
		this.cacheConceptsAndProperties();

		Rules r = new Rules();
		KBObject robj = kb.getPropertyValue(tobj, this.propertyObjMap.get("hasRules"));
		if (robj == null)
			return r;

		// Fetch rules
		KBObject val = kb.getPropertyValue(robj, propertyObjMap.get("hasRules"));
		if (val != null)
			r.setRulesText((String) val.getValue());

		return r;
	}

	private Port readPortFromKB(KBObject portObj) {
		Port port = new Port(portObj.getID());
		KBObject roleObj = kb.getPropertyValue(portObj, propertyObjMap.get("satisfiesRole"));
		if (roleObj != null) {
			Role role = new Role(roleObj.getID());
			// Get Role ID string
			if(!role.getNamespace().equals(this.getNamespace())) {
				// Legacy compatibility
				// Role ids were just the localname of the role id (and ids were in the component catalog's namespace)
				role.setRoleId(role.getName());
				// Create a new role id
				role.setID(portObj.getID()+"_role");
			}
			else {
				// Now we have a "hasRoleID" property which has string range 
				KBObject roleid = kb.getDatatypePropertyValue(roleObj, propertyObjMap.get("hasRoleID"));
				if(roleid != null)
					role.setRoleId((String)roleid.getValue());
			}
			// Get Role Dimensionality
			KBObject dim = kb.getDatatypePropertyValue(roleObj,
					propertyObjMap.get("hasDimensionality"));
			if (dim != null && dim.getValue() != null)
				role.setDimensionality((Integer) dim.getValue());
			port.setRole(role);
		}
		return port;
	}

	private Variable[] getLinkVariables(Link[] links) {
		HashSet<Variable> varAr = new HashSet<Variable>();
		for (int i = 0; i < links.length; i++) {
			Variable var = links[i].getVariable();
			varAr.add(var);
		}
		return varAr.toArray(new Variable[varAr.size()]);
	}

	public Link[] getInputLinks() {
		ArrayList<Link> links = new ArrayList<Link>();
		for (Link l : Links) {
			if (l.isInputLink()) {
				links.add(l);
			}
		}
		return links.toArray(new Link[0]);
	}

	public Link[] getInputLinks(Node n) {
		ArrayList<Link> links = new ArrayList<Link>();
		for (Link l : Links) {
			if (l.getDestinationNode() != null && l.getDestinationNode().equals(n)) {
				links.add(l);
			}
		}
		return links.toArray(new Link[0]);
	}

	public Variable[] getInputVariables() {
		return getLinkVariables(getInputLinks());
	}

	public Variable[] getInputVariables(Node n) {
		return getLinkVariables(getInputLinks(n));
	}

	public Link[] getIntermediateLinks() {
		ArrayList<Link> links = new ArrayList<Link>();
		for (Link l : Links) {
			if (l.isInOutLink()) {
				links.add(l);
			}
		}
		return links.toArray(new Link[0]);
	}

	public Variable[] getIntermediateVariables() {
		return getLinkVariables(getIntermediateLinks());
	}

	public Link getLink(Node fromN, Node toN, Port fromPort, Port toPort) {
		for (Link l : Links) {
			boolean ok = true;
			if(l.getOriginNode() != null && fromN != null && !l.getOriginNode().getID().equals(fromN.getID()))
				ok = false;
			if(l.getOriginPort() != null && fromPort != null && !l.getOriginPort().getID().equals(fromPort.getID()))
				ok = false;
			if(l.getDestinationNode() != null && toN != null && !l.getDestinationNode().getID().equals(toN.getID()))
				ok = false;
			if(l.getDestinationPort() != null && toPort != null && !l.getDestinationPort().getID().equals(toPort.getID()))
				ok = false;
			if(l.getOriginNode() == null && fromN != null)
				ok = false;
			if(l.getDestinationNode() == null && toN != null)
				ok = false;
			
			if(ok)
				return l;
		}
		return null;
	}

	public Link[] getLinks() {
		return Links;
	}

	public Link[] getLinks(Node fromN, Node toN) {
		ArrayList<Link> links = new ArrayList<Link>();
		for (Link l : Links) {
			if (l.getOriginNode().equals(fromN) && l.getDestinationNode().equals(toN)) {
				links.add(l);
			}
		}
		return links.toArray(new Link[0]);
	}

	public Link[] getLinks(Variable v) {
		ArrayList<Link> links = new ArrayList<Link>();
		for (Link l : Links) {
			if (l.getVariable().equals(v)) {
				links.add(l);
			}
		}
		return links.toArray(new Link[0]);
	}

	public Node[] getNodes() {
		return Nodes;
	}

	public ComponentVariable getComponentVariable(String cid) {
		for (Node n : Nodes) {
			if (n.getComponentVariable().getID().equals(cid))
				return n.getComponentVariable();
		}
		return null;
	}

	public Node getNode(String id) {
		for (Node n : Nodes) {
			if (n.getID().equals(id)) {
				return n;
			}
		}
		return null;
	}

	public Link getLink(String id) {
		for (Link l : Links) {
			if (l.getID().equals(id)) {
				return l;
			}
		}
		return null;
	}

	public Variable getVariable(String id) {
		for (Variable v : Variables) {
			if (v.getID().equals(id)) {
				return v;
			}
		}
		return null;
	}

	public Link[] getOutputLinks() {
		ArrayList<Link> links = new ArrayList<Link>();
		for (Link l : Links) {
			if (l.isOutputLink()) {
				links.add(l);
			}
		}
		return links.toArray(new Link[0]);
	}

	public Link[] getOutputLinks(Node n) {
		ArrayList<Link> links = new ArrayList<Link>();
		for (Link l : Links) {
			if (l.getOriginNode() != null && l.getOriginNode().equals(n)) {
				links.add(l);
			}
		}
		return links.toArray(new Link[0]);
	}

	public Variable[] getOutputVariables() {
		return getLinkVariables(getOutputLinks());
	}

	public Variable[] getOutputVariables(Node n) {
		return getLinkVariables(getOutputLinks(n));
	}

	public Variable[] getVariables() {
		return Variables;
	}

	private String createLinkId(Port fromPort, Port toPort) {
		String lid = this.getNamespace();
		if (fromPort != null)
			lid += fromPort.getName() + "_To";
		else
			lid += "Input_To";

		if (toPort != null)
			lid += "_" + toPort.getName();
		else
			lid += "_Output";
		return lid;
	}
	
	public Link addLink(Node fromN, Node toN, Port fromPort, Port toPort, Variable var) {
	    	String lid = this.createLinkId(fromPort, toPort);
	    	
		int i = 1;
		while (getLink(lid) != null) {
			lid += "_" + i;
		}

		Link l = new Link(lid, fromN, toN, fromPort, toPort);
		if (toN != null && toN.findInputPort(toPort.getID()) == null)
			toN.addInputPort(toPort);

		if (fromN != null && fromN.findInputPort(fromPort.getID()) == null)
			fromN.addOutputPort(fromPort);

		if (var != null) {
			if (getVariable(var.getID()) == null)
				addVariable(var);
			l.setVariable(var);
		}

		Link[] links = new Link[Links.length + 1];
		for (i = 0; i < Links.length; i++) {
			links[i] = Links[i];
		}
		links[Links.length] = l;
		Links = links;

		return l;
	}

	public void deleteLink(Link l) {
		Link[] links = new Link[Links.length - 1];
		int j = 0;
		for (Link el : Links) {
			if (!el.equals(l)) {
				links[j] = el;
				j++;
			}
		}
		Links = links;

		if (this.getLinks(l.getVariable()).length == 0) {
			deleteVariable(l.getVariable());
		}
		if (l.getDestinationNode() != null)
			l.getDestinationNode().deleteInputPort(l.getDestinationPort());
		if (l.getOriginNode() != null)
			l.getOriginNode().deleteOutputPort(l.getOriginPort());
	}

	private void addVariable(Variable var) {
		Variable[] variables = new Variable[Variables.length + 1];
		for (int i = 0; i < Variables.length; i++) {
			variables[i] = Variables[i];
		}
		variables[Variables.length] = var;
		Variables = variables;
	}

	public void deleteVariable(Variable v) {
		if (Variables.length == 0)
			return;
		Variable[] variables = new Variable[Variables.length - 1];
		int j = 0;
		for (Variable el : Variables) {
			if (!el.equals(v)) {
				variables[j] = el;
				j++;
			}
		}
		Variables = variables;
	}

	public Variable addVariable(String varid, short type) {
		String vid = varid;
		int i = 1;
		while (getVariable(vid) != null) {
			vid = varid + "_" + i;
			i++;
		}
		Variable v = new Variable(vid, type);
		this.addVariable(v);
		return v;
	}
	
	public Node addNode(ComponentVariable c) {
		String cid = c.getName();
		String nodeid = this.getNamespace() + cid + "Node";
		String nid = nodeid;
		int i = 1;
		while (getNode(nid) != null) {
			nid = nodeid + "_" + i;
			i++;
		}
		Node n = new Node(nid);
		n.setComponentVariable(c);

		Node[] nodes = new Node[Nodes.length + 1];
		for (i = 0; i < Nodes.length; i++) {
			nodes[i] = Nodes[i];
		}
		nodes[Nodes.length] = n;
		Nodes = nodes;

		// this.addDefaultSetCreationRulesForNode(n);
		return n;
	}

	public void deleteNode(Node n) {
		Node[] nodes = new Node[Nodes.length - 1];
		int j = 0;
		for (Node element : Nodes) {
			if (!element.equals(n)) {
				nodes[j] = element;
				j++;
			}
		}
		Nodes = nodes;

		// Delete or Modify input/output links to/from the node
		for (Link l : getInputLinks(n)) {
			if (l.isInputLink()) {
				deleteLink(l);
			} else {
				l.setDestinationNode(null);
				l.setDestinationPort(null);
			}
		}
		for (Link l : getOutputLinks(n)) {
			if (l.isOutputLink()) {
				deleteLink(l);
			} else {
				l.setOriginNode(null);
				l.setOriginPort(null);
			}
		}
	}

	public void setVariableBinding(Variable v, Binding b) {
		v.setBinding(b);
	}

	public void setVariableBinding(Variable v, ValueBinding b) {
		v.setBinding(b);
	}
	
	/*public Template createCopy1() {
		TemplateKB t = (TemplateKB) SerializableObjectCloner.clone(this);
		// Create a plain new KBAPI
		t.kb = ontologyFactory.getAPI(OntSpec.PLAIN);
		// t.kb.setNamespacePrefixMap(kb.getNamespacePrefixMap());
		t.kb.copyFrom(kb);
		// t.kb = fillKBWithDerivedRepresentation(t.kb, false);
		t.constraintEngine = new ConstraintEngineKB(t.kb, t.wflowns);
		return t;
	}*/

	private ValueBinding copyTemplateBindings(ValueBinding b) {
		if (b.isSet()) {
			ValueBinding rb = new ValueBinding();
			for (WingsSet bb : b)
				rb.add(copyTemplateBindings((ValueBinding) bb));
			return rb;
		} else
			return new ValueBinding(((Template) b.getValue()).createCopy());
	}

	public Template createCopy() {
		TemplateKB t = new TemplateKB(this);
		t.getMetadata().createdFrom.add(this.getID());

		t.setID(this.getID());
		HashMap<Node, Node> map = new HashMap<Node, Node>();

		for (Node e : Nodes) {
			// Add New Nodes
			ComponentVariable ev = e.getComponentVariable();
			ComponentVariable cv;
			
			// Create new component variable
			if (ev.isTemplate()) {
				cv = new ComponentVariable(ev.getTemplate());
			} else {
				cv = new ComponentVariable(ev.getID());
			}
			
			// Copy component "isConcrete" property
			cv.setConcrete(ev.isConcrete());

			// Copy component bindings
			if (ev.getBinding() != null) {
				if (ev.isTemplate()) {
					cv.setBinding(copyTemplateBindings((ValueBinding) ev.getBinding()));

				} else
					cv.setBinding((Binding) SerializableObjectCloner.clone(ev.getBinding()));
			}
			
			// Copy node details
			Node n = t.addNode(cv);
			n.setID(e.getID());
			n.setComment(e.getComment());

			// Copy over ports
			for (Port p : e.getInputPorts()) {
				Port np = new Port(p.getID());
				np.setRole(p.getRole());
				n.addInputPort(np);
			}
			for (Port p : e.getOutputPorts()) {
				Port np = new Port(p.getID());
				np.setRole(p.getRole());
				n.addOutputPort(np);
			}

			// Copy rules for creating component/workflow sets
			ComponentSetCreationRule crule = e.getComponentSetRule();
			PortSetCreationRule prule = e.getPortSetRule();
			if (crule != null) {
				ComponentSetCreationRule ncrule = new ComponentSetCreationRule(crule.getType());
				n.addComponentSetRule(ncrule);
			}
			if (prule != null) {
				SetExpression expr = prule.getSetExpression();
				SetExpression nexpr = copySetExpression(n, expr);

				PortSetCreationRule nprule = new PortSetCreationRule(prule.getType(), nexpr);
				n.addPortSetRule(nprule);
			}

			map.put(e, n);
		}

		ArrayList<String> varids = new ArrayList<String>();

		// Copy links
		for (int i = 0; i < Links.length; i++) {
			Link l = Links[i];
			Node fromNode = null, toNode = null;
			Port fromPort = null, toPort = null;
			if (l.getOriginNode() != null) {
				fromNode = map.get(l.getOriginNode());
				fromPort = fromNode.findOutputPort(l.getOriginPort().getID());
			}
			if (l.getDestinationNode() != null) {
				toNode = map.get(l.getDestinationNode());
				toPort = toNode.findInputPort(l.getDestinationPort().getID());
			}

			// Copy Variable
			Variable v = l.getVariable();
			Variable vv = t.getVariable(v.getID());
			if (vv == null) {
				if (v.isDataVariable()) {
					vv = new DataVariable(v.getID());
					if (v.getBinding() != null) {
						vv.setBinding((Binding) SerializableObjectCloner.clone(v.getBinding()));
					}
				} else if (v.isParameterVariable()) {
					vv = new ParameterVariable(v.getID());
					if (v.getBinding() != null) {
						vv.setBinding((Binding) SerializableObjectCloner.clone(v.getBinding()));
					}
				}
				vv.setComment(v.getComment());
			}
			if (vv != null) {
				Link ll = t.addLink(fromNode, toNode, fromPort, toPort, vv);
				ll.setID(Links[i].getID());

				varids.add(vv.getID());
			}
		}

		// Copy template input and output roles
		for (String varid : inputRoles.keySet()) {
			Role r = inputRoles.get(varid);
			Role nr = new Role(r.getID());
			nr.setDimensionality(r.getDimensionality());
			nr.setRoleId(r.getRoleId());
			nr.setType(r.getType());
			t.addInputRole(varid, nr);
		}
		for (String varid : outputRoles.keySet()) {
			Role r = outputRoles.get(varid);
			Role nr = new Role(r.getID());
			nr.setDimensionality(r.getDimensionality());
			nr.setRoleId(r.getRoleId());
			nr.setType(r.getType());
			t.addOutputRole(varid, nr);
		}

		// Copy Variable Constraints
		t.getConstraintEngine().addConstraints(this.constraintEngine.getConstraints(varids));

		// Recache concepts and properties
		t.cacheConceptsAndProperties();

		return t;
	}

	public ConstraintEngine getConstraintEngine() {
		return this.constraintEngine;
	}

	public String toString() {
		String hyphen = "-";
		String space = " ";
		String comma = ",";
		String equals = "=";
		String openParen = "(";
		String closeParen = ")";
		StringBuilder componentDescription = new StringBuilder();

		int size = Nodes.length;
		int counter = 0;
		for (Node node : Nodes) {
			// String cname = this.getURIName(node.getComponent().getID());
			String cname = node.getComponentVariable().toString();
			componentDescription.append(cname);
			if (++counter != size) {
				componentDescription.append(hyphen);
			}
		}

		StringBuilder dataVariables = new StringBuilder();
		StringBuilder parameters = new StringBuilder();
		ArrayList<Variable> parameterVariablesWithBindings = new ArrayList<Variable>();
		ArrayList<Variable> dataVariablesWithDataBindings = new ArrayList<Variable>();
		Variable[] inputVariables = this.getInputVariables();
		for (Variable inputVariable : inputVariables) {
			if (inputVariable.isDataVariable()) {
				Binding binding = inputVariable.getBinding();
				if (binding != null) {
					dataVariablesWithDataBindings.add(inputVariable);
				}
			} else {
				Binding binding = inputVariable.getBinding();
				if (binding != null) {
					parameterVariablesWithBindings.add(inputVariable);
				}
			}
		}

		parameters.append(openParen);
		if (parameterVariablesWithBindings.isEmpty()) {
			parameters.append("unbound parameters");
		} else {
			size = parameterVariablesWithBindings.size();
			counter = 0;
			for (Variable parameterVariable : parameterVariablesWithBindings) {
				String uriName = this.getURIName(parameterVariable.getID());
				Object value = parameterVariable.getBinding();
				parameters.append(uriName);
				parameters.append(equals);
				parameters.append(value);
				if (++counter != size) {
					parameters.append(comma);
				}
			}
		}
		parameters.append(closeParen);

		dataVariables.append(openParen);
		if (dataVariablesWithDataBindings.isEmpty()) {
			dataVariables.append("unbound data variables");
		} else {
			size = dataVariablesWithDataBindings.size();
			counter = 0;
			for (Variable dataVariable : dataVariablesWithDataBindings) {
				String uriName = this.getURIName(dataVariable.getID());
				Binding binding = dataVariable.getBinding();
				String dataObjectName;
				if (binding == null) {
					dataObjectName = "null";
				} else {
					dataObjectName = binding.getName();
				}
				dataVariables.append(uriName);
				dataVariables.append(equals);
				dataVariables.append(dataObjectName);
				if (++counter != size) {
					dataVariables.append(comma);
				}
			}
		}
		dataVariables.append(closeParen);

		StringBuilder result = new StringBuilder();
		// result.append("Template");
		// result.append(this.getTemplateId());
		result.append(openParen);
		result.append(componentDescription.toString());
		result.append(space);
		result.append(dataVariables.toString());
		result.append(space);
		result.append(parameters.toString());
		result.append(closeParen);
		return result.toString();

	}

	public String getURIName(String url) {
		return url.substring(url.indexOf('#') + 1);
	}

	public KBAPI getKBCopy(boolean includeDataConstraints) {
		// Create a new temporary kb
		KBAPI tkb = ontologyFactory.getKB(new ByteArrayInputStream(getInternalRepresentation()
				.getBytes()), this.getNamespace(), OntSpec.PLAIN);

		if (includeDataConstraints) {
			ArrayList<String> varids = new ArrayList<String>();
			for (Variable v : Variables)
				varids.add(v.getID());
			tkb.addTriples(this.constraintEngine.getConstraints(varids));
		}

		return tkb;
	}

	public String serialize() {
		KBAPI tkb = this.serializeAndGetKB();
		// Return RDF representation
		// return tkb.toN3(this.url);
		return tkb.toAbbrevRdf(false, this.getURL());
	}
	
	public void resetInternalRepresentation() {
		this.kb = this.serializeAndGetKB();
		try {
			this.kb.importFrom(ontologyFactory.getKB(this.onturl, OntSpec.PLAIN));
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		this.constraintEngine = new ConstraintEngineKB(this.kb, this.wflowns);
	}
	
	private KBAPI serializeAndGetKB() {
		// If this template has no ontology backing it, then initialize the API
		if(ontologyFactory == null || kb == null) {
			this.initVariables(this.props);
			this.initializeKB(this.props, this.Nodes.length == 0); 
		}
		// Create a plain new KBAPI
		KBAPI tkb = ontologyFactory.getKB(OntSpec.PLAIN);
		tkb = serializeIntoKB(tkb, false);
		return tkb;
	}

	public boolean save() {
		KBAPI tkb = null;
		try {
			tkb = ontologyFactory.getKB(OntSpec.PLAIN);
			serializeIntoKB(tkb, false);
			return tkb.saveAs(this.getURL());
		}
		catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		finally {
			if(tkb != null)
				tkb.end();
		}
	}
	
	public void end() {
		if (this.kb != null)
			this.kb.end();
	}
	
	public boolean saveAs(String newid) {
		String curns = this.getNamespace();
		this.setID(newid);
		String newns = this.getNamespace();
		KBAPI tkb = null;
		try {
			tkb = ontologyFactory.getKB(OntSpec.PLAIN);
			serializeIntoKB(tkb, false);
			KBUtils.renameTripleNamespace(tkb, curns, newns);
			return tkb.saveAs(this.getURL());
		}
		catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		finally {
			if(tkb != null)
				tkb.end();
		}
	}
	
	public boolean delete() {
		return kb.delete();
	}
	
	private Binding readBindingObjectFromKB(KBAPI tkb, KBObject bobj) {
		if (bobj == null)
			return null;
		Binding b = new Binding(this.getNamespace() + UuidGen.generateAUuid(""));
		if (bobj.isList()) {
			ArrayList<KBObject> items = tkb.getListItems(bobj);
			for (KBObject item : items) {
				b.add(readBindingObjectFromKB(tkb, item));
			}
		} else if (bobj.getID() != null) {
			b.setID(bobj.getID());
		}
		return b;
	}

	private ValueBinding readValueBindingObjectFromKB(KBAPI tkb, KBObject bobj) {
		ValueBinding b = new ValueBinding(this.getNamespace() + UuidGen.generateAUuid(""));
		if (bobj.isList()) {
			ArrayList<KBObject> items = tkb.getListItems(bobj);
			for (KBObject item : items) {
				b.add(readValueBindingObjectFromKB(tkb, item));
			}
		} else {
			b.setValue(bobj.getValue());
		}
		return b;
	}

	private KBObject writeBindingObjectToKB(KBAPI tkb, Binding b) {
		if (!b.isSet())
			return tkb.getResource(b.getID());

		ArrayList<KBObject> listItems = new ArrayList<KBObject>();
		for (WingsSet a : b) {
			listItems.add(writeBindingObjectToKB(tkb, (Binding) a));
		}
		return tkb.createList(listItems);
	}

	private KBObject writeValueBindingObjectToKB(KBAPI tkb, ValueBinding b) {
		if (!b.isSet()) {
			if(b.getDatatype() != null)
				return tkb.createXSDLiteral(b.getValue().toString(), b.getDatatype());
			else
				return ontologyFactory.getDataObject(b.getValue());
		}

		ArrayList<KBObject> listItems = new ArrayList<KBObject>();
		for (WingsSet a : b) {
			listItems.add(writeValueBindingObjectToKB(tkb, (ValueBinding) a));
		}
		return tkb.createList(listItems);
	}

	private KBAPI serializeIntoKB(KBAPI tkb, boolean subtemplate) {
		cacheConceptsAndProperties();
		autoUpdateTemplateRoles();

		HashMap<String, KBObject> pmap = propertyObjMap;
		HashMap<String, KBObject> cmap = conceptObjMap;

		// Copy over the namespace mappings
		// TODO: Add just the subtemplate import if this is an external
		// subtemplate
		if (!subtemplate) {
			// Create the ontology object
			tkb.createImport(this.getURL(), this.onturl);
		}

		// Create a template
		KBObject tobj = tkb.createObjectOfClass(this.getID(), cmap.get("WorkflowTemplate"));

		// Create the Nodes
		for (Node n : Nodes) {
			KBObject nobj = tkb.createObjectOfClass(n.getID(), cmap.get("Node"));
			tkb.addPropertyValue(tobj, pmap.get("hasNode"), nobj);

			ComponentVariable c = n.getComponentVariable();
			if (c != null && !c.isTemplate()) {
				KBObject cobj = tkb.getResource(c.getID());
				tkb.addClassForInstance(cobj, cmap.get("ComponentVariable"));

				if (cobj != null) {
					tkb.addPropertyValue(nobj, pmap.get("hasComponent"), cobj);
					if (c.isConcrete())
						tkb.addPropertyValue(cobj, pmap.get("isConcrete"),
								ontologyFactory.getDataObject(true));
					if (c.getBinding() != null) {
						tkb.addPropertyValue(cobj, pmap.get("hasComponentBinding"),
								writeBindingObjectToKB(tkb, c.getBinding()));
					}
				}
			} else if (c != null && c.isTemplate()) {
				TemplateKB subt = (TemplateKB) c.getTemplate();
				tkb.addPropertyValue(nobj, pmap.get("hasWorkflow"),
						tkb.getResource(subt.getNamespace() + subt.getName()));

				// recurse here for subtemplates
				String suburl = subt.getURL();
				if (kb.getImports(this.getURL()).contains(suburl)) {
					tkb.createImport(this.getURL(), suburl);
				} else {
					tkb = subt.serializeIntoKB(tkb, true);
				}
			}

			if (nobj != null && n.getComment() != null) {
				double[] pos = LayoutHelper.parseCommentString(n.getComment());
				if (pos != null)
					tkb.setComment(nobj, LayoutHelper.createCommentString(pos[0], pos[1]));
			}

			// Add Port information
			for (Port p : n.getInputPorts()) {
				KBObject pobj = tkb.createObjectOfClass(p.getID(), cmap.get("Port"));
				if (p.getRole() != null) {
					Role r = p.getRole();
					KBObject roleobj = tkb.getResource(r.getID());
					tkb.setPropertyValue(roleobj, propertyObjMap.get("hasDimensionality"),
							ontologyFactory.getDataObject(r.getDimensionality()));
					tkb.setPropertyValue(roleobj, propertyObjMap.get("hasRoleID"),
							ontologyFactory.getDataObject(r.getRoleId()));
					tkb.addPropertyValue(pobj, pmap.get("satisfiesRole"), roleobj);
				}
				tkb.addPropertyValue(nobj, pmap.get("hasInputPort"), pobj);
			}

			for (Port p : n.getOutputPorts()) {
				KBObject pobj = tkb.createObjectOfClass(p.getID(), cmap.get("Port"));
				if (p.getRole() != null) {
					Role r = p.getRole();
					KBObject roleobj = tkb.getResource(r.getID());
					tkb.setPropertyValue(roleobj, propertyObjMap.get("hasDimensionality"),
							ontologyFactory.getDataObject(r.getDimensionality()));
					tkb.setPropertyValue(roleobj, propertyObjMap.get("hasRoleID"),
							ontologyFactory.getDataObject(r.getRoleId()));
					tkb.addPropertyValue(pobj, pmap.get("satisfiesRole"), roleobj);
				}
				tkb.addPropertyValue(nobj, pmap.get("hasOutputPort"), pobj);
			}

			// Add Set Creation Rules information
			ComponentSetCreationRule crule = n.getComponentSetRule();
			PortSetCreationRule prule = n.getPortSetRule();

			if (crule != null) {
				KBObject cruleobj = tkb.createObjectOfClass(n.getID()+"_crule", cmap.get("ComponentSetRule"));
				if (crule.getType() == SetType.STYPE) {
					tkb.addPropertyValue(cruleobj, pmap.get("createComponentSets"),
							ontologyFactory.getDataObject(true));
				} else if (crule.getType() == SetType.WTYPE) {
					tkb.addPropertyValue(cruleobj, pmap.get("createWorkflowSets"),
							ontologyFactory.getDataObject(true));
				}
				tkb.addPropertyValue(cruleobj, pmap.get("createSetsOn"),
						tkb.getResource(n.getComponentVariable().getID()));
				tkb.addPropertyValue(nobj, pmap.get("hasComponentSetCreationRule"), cruleobj);
			}
			if (prule != null) {
				KBObject pruleobj = tkb.createObjectOfClass(n.getID()+"_prule", cmap.get("PortSetRule"));
				if (prule.getType() == SetType.STYPE) {
					tkb.addPropertyValue(pruleobj, pmap.get("createComponentSets"),
							ontologyFactory.getDataObject(true));
				} else if (prule.getType() == SetType.WTYPE) {
					tkb.addPropertyValue(pruleobj, pmap.get("createWorkflowSets"),
							ontologyFactory.getDataObject(true));
				}

				SetExpression expr = prule.getSetExpression();
				if (expr != null) {
					KBObject exprobj = writeSetExpressionInKB(tkb, expr);
					tkb.addPropertyValue(pruleobj, pmap.get("createSetsOn"), exprobj);
				}
				tkb.addPropertyValue(nobj, pmap.get("hasPortSetCreationRule"), pruleobj);
			}
		}

		// Create Variables
		for (Variable v : Variables) {
			KBObject vobj = null;
			if (v.isDataVariable()) {
				vobj = tkb.createObjectOfClass(v.getID(), cmap.get("DataVariable"));
				if (v.getBinding() != null) {
					tkb.addPropertyValue(vobj, pmap.get("hasDataBinding"),
							writeBindingObjectToKB(tkb, v.getBinding()));
				}
			} else if (v.isParameterVariable()) {
				vobj = tkb.createObjectOfClass(v.getID(), cmap.get("ParameterVariable"));
				if (v.getBinding() != null) {
					tkb.addPropertyValue(vobj, pmap.get("hasParameterValue"),
							writeValueBindingObjectToKB(tkb, (ValueBinding)v.getBinding()));
				}
			}
			if (vobj != null && v.getComment() != null) {
				double[] pos = LayoutHelper.parseCommentString(v.getComment());
				if (pos != null)
					tkb.setComment(vobj, LayoutHelper.createCommentString(pos[0], pos[1]));
			}
		}

		// Create Links
		for (Link l : Links) {
			KBObject lc = null;
			if (l.isInputLink())
				lc = cmap.get("InputLink");
			else if (l.isInOutLink())
				lc = cmap.get("InOutLink");
			else if (l.isOutputLink())
				lc = cmap.get("OutputLink");
			if (lc != null) {
				KBObject lobj = tkb.createObjectOfClass(l.getID(), lc);
				tkb.addPropertyValue(tobj, pmap.get("hasLink"), lobj);

				Node on = l.getOriginNode();
				Node dn = l.getDestinationNode();
				Port ocp = l.getOriginPort();
				Port dcp = l.getDestinationPort();
				Variable v = l.getVariable();

				if (on != null)
					tkb.addPropertyValue(lobj, pmap.get("hasOriginNode"),
							tkb.getResource(on.getID()));
				if (ocp != null)
					tkb.addPropertyValue(lobj, pmap.get("hasOriginPort"),
							tkb.getResource(ocp.getID()));
				if (dn != null)
					tkb.addPropertyValue(lobj, pmap.get("hasDestinationNode"),
							tkb.getResource(dn.getID()));
				if (dcp != null)
					tkb.addPropertyValue(lobj, pmap.get("hasDestinationPort"),
							tkb.getResource(dcp.getID()));
				if (v != null)
					tkb.addPropertyValue(lobj, pmap.get("hasVariable"),
							tkb.getResource(v.getID()));
			}
		}

		writeTemplateRolesInKB(tkb, tobj);

		if (!subtemplate) {
			// Add variable constraints
			ArrayList<String> varids = new ArrayList<String>();
			for (Variable v : getVariables())
				varids.add(v.getID());
			tkb.addTriples(getConstraintEngine().getConstraints(varids));

			this.version = latestVersion;

			tkb.addPropertyValue(tobj, pmap.get("hasVersion"),
					ontologyFactory.getDataObject(this.version));

			writeMetadataDescription(tkb, tobj, metadata);
			writeRules(tkb, tobj, rules);
		}

		return tkb;
	}

	private KBObject writeSetExpressionInKB(KBAPI tkb, SetExpression expr) {
		KBObject exprobj = null;

		String ns = this.getNamespace();
		if (expr.isSet()) {
			if (expr.getOperator() == SetOperator.XPRODUCT) {
				exprobj = tkb.createObjectOfClass(ns + UuidGen.generateAUuid("_xprod"),
						conceptObjMap.get("XProduct"));
			} else if (expr.getOperator() == SetOperator.NWISE) {
				exprobj = tkb.createObjectOfClass(ns + UuidGen.generateAUuid("_nwise"),
						conceptObjMap.get("NWise"));
			} else if (expr.getOperator() == SetOperator.INCREASEDIM) {
				exprobj = tkb.createObjectOfClass(ns + UuidGen.generateAUuid("_dim"),
						conceptObjMap.get("IncreaseDimensionality"));
			} else if (expr.getOperator() == SetOperator.REDUCEDIM) {
				exprobj = tkb.createObjectOfClass(ns + UuidGen.generateAUuid("_rdim"),
						conceptObjMap.get("ReduceDimensionality"));
			} else if (expr.getOperator() == SetOperator.SHIFT) {
				exprobj = tkb.createObjectOfClass(ns + UuidGen.generateAUuid("_shift"),
						conceptObjMap.get("Shift"));
			}

			for (SetExpression cexpr : expr) {
				KBObject cexprobj = writeSetExpressionInKB(tkb, cexpr);
				tkb.addPropertyValue(exprobj, propertyObjMap.get("hasExpressionArgument"),
						cexprobj);
			}
		} else if (expr.getPort() != null) {
			exprobj = tkb.getResource(expr.getPort().getID());
		}
		return exprobj;
	}

	private void writeTemplateRolesInKB(KBAPI tkb, KBObject templateObj) {
		for (String varid : inputRoles.keySet()) {
			Role r = inputRoles.get(varid);
			KBObject roleobj = tkb.createObjectOfClass(r.getID(), conceptObjMap.get("Role"));
			tkb.addPropertyValue(templateObj, propertyObjMap.get("hasInputRole"), roleobj);
			tkb.setPropertyValue(roleobj, propertyObjMap.get("mapsToVariable"),
					tkb.getResource(varid));
			tkb.setPropertyValue(roleobj, propertyObjMap.get("hasRoleID"),
					ontologyFactory.getDataObject(r.getRoleId()));
			tkb.setPropertyValue(roleobj, propertyObjMap.get("hasDimensionality"),
					ontologyFactory.getDataObject(r.getDimensionality()));
		}
		for (String varid : outputRoles.keySet()) {
			Role r = outputRoles.get(varid);
			KBObject roleobj = tkb.createObjectOfClass(r.getID(), conceptObjMap.get("Role"));
			tkb.addPropertyValue(templateObj, propertyObjMap.get("hasOutputRole"), roleobj);
			tkb.setPropertyValue(roleobj, propertyObjMap.get("mapsToVariable"),
					tkb.getResource(varid));
			tkb.setPropertyValue(roleobj, propertyObjMap.get("hasRoleID"),
					ontologyFactory.getDataObject(r.getRoleId()));
			tkb.setPropertyValue(roleobj, propertyObjMap.get("hasDimensionality"),
					ontologyFactory.getDataObject(r.getDimensionality()));
		}
	}

	private SetExpression readSetExpressionFromKB(KBAPI tkb, KBObject exprobj, Node n) {
		SetExpression expr = null;

		KBObject exprcls = tkb.getClassOfInstance(exprobj);
		if(exprcls == null) return expr;

		boolean isleaf = false;
		if (exprcls.getID().equals(conceptObjMap.get("XProduct").getID())) {
			expr = new SetExpression(SetOperator.XPRODUCT);
		} else if (exprcls.getID().equals(conceptObjMap.get("NWise").getID())) {
			expr = new SetExpression(SetOperator.NWISE);
		} else if (exprcls.getID().equals(conceptObjMap.get("IncreaseDimensionality").getID())) {
			expr = new SetExpression(SetOperator.INCREASEDIM);
		} else if (exprcls.getID().equals(conceptObjMap.get("ReduceDimensionality").getID())) {
			expr = new SetExpression(SetOperator.REDUCEDIM);
		} else if (exprcls.getID().equals(conceptObjMap.get("Shift").getID())) {
			expr = new SetExpression(SetOperator.SHIFT);
		} else if (exprcls.getID().equals(conceptObjMap.get("Port").getID())) {
			Port p = n.findInputPort(exprobj.getID());
			if (p == null)
				return null;

			expr = new SetExpression(SetOperator.XPRODUCT, p);
			isleaf = true;
		}

		if (!isleaf) {
			ArrayList<KBObject> argobjs = tkb.getPropertyValues(exprobj,
					propertyObjMap.get("hasExpressionArgument"));
			if (argobjs != null) {
				for (KBObject argobj : argobjs) {
					SetExpression cexpr = readSetExpressionFromKB(tkb, argobj, n);
					if (cexpr != null)
						expr.add(cexpr);
				}
			}
		}
		return expr;
	}

	private SetExpression copySetExpression(Node n, SetExpression expr) {
		if (expr.isSet()) {
			SetExpression nexpr = new SetExpression(expr.getOperator());
			for (SetExpression cexpr : expr)
				nexpr.add(copySetExpression(n, cexpr));
			return nexpr;
		} else {
			return new SetExpression(expr.getOperator(), n.findInputPort(expr.getPort().getID()));
		}
	}

	protected void writeMetadataDescription(KBAPI tkb, KBObject tobj, Metadata m) {
		// Add metadata
		this.cacheConceptsAndProperties();
		KBObject mobj = tkb.createObjectOfClass(this.getID()+"_meta", conceptObjMap.get("Metadata"));
		tkb.setPropertyValue(tobj, propertyObjMap.get("hasMetadata"), mobj);

		if(m.lastUpdate != null && m.lastUpdateTime == null) {
			try {
				Calendar cal = DatatypeFactory.newInstance().newXMLGregorianCalendar(m.lastUpdate).toGregorianCalendar();
				m.lastUpdateTime = new XSDDateTime(cal);
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (m.lastUpdateTime != null)
			tkb.setPropertyValue(mobj, propertyObjMap.get("lastUpdateTime"),
					ontologyFactory.getDataObject(m.getLastUpdateTime()));
			
		if (m.documentation != null)
			tkb.setPropertyValue(mobj, propertyObjMap.get("hasDocumentation"),
					ontologyFactory.getDataObject(m.documentation));

		for (String tmp : m.createdFrom)
			if (tmp != null)
				tkb.addPropertyValue(mobj, propertyObjMap.get("createdFrom"),
						ontologyFactory.getDataObject(tmp));
		for (String tmp : m.contributors)
			if (tmp != null)
				tkb.addPropertyValue(mobj, propertyObjMap.get("hasContributor"),
						ontologyFactory.getDataObject(tmp));
	}

	protected void writeRules(KBAPI tkb, KBObject tobj, Rules rules) {
		// Add rules
		this.cacheConceptsAndProperties();
		if (rules.getRulesText() != null)
			tkb.setPropertyValue(tobj, propertyObjMap.get("hasRules"),
					ontologyFactory.getDataObject(rules.getRulesText()));
	}

	public Template getCreatedFrom() {
		return this.createdFrom;
	}

	public void setCreatedFrom(Template createdFrom) {
		this.createdFrom = createdFrom;
	}

	public Template getParent() {
		return this.parent;
	}

	public void setParent(Template parent) {
		this.parent = parent;
	}

	public Metadata getMetadata() {
		return this.metadata;
	}

	public Rules getRules() {
		return this.rules;
	}

	public Template applyRules() {
		KBAPI tkb = getKBCopy(true);

		String ns = this.wflowns;
		ArrayList<KBObject> templates = tkb.getInstancesOfClass(
				tkb.getConcept(ns + "WorkflowTemplate"), false);
		if (templates == null || templates.size() == 0)
			return null;

		HashMap<String, String> rulePrefixNSMap = new HashMap<String, String>();
		rulePrefixNSMap.put("rdf", KBUtils.RDF);
		rulePrefixNSMap.put("rdfs", KBUtils.RDFS);
		rulePrefixNSMap.put("xsd", KBUtils.XSD);
		rulePrefixNSMap.put("owl", KBUtils.OWL);
		rulePrefixNSMap.put("wflow", this.wflowns);
		tkb.setRulePrefixes(rulePrefixNSMap);

		// System.out.println(instance.getRules().getRulesText());
		String ruleText = getRules().getRulesText();
		ruleText = ruleText.replaceAll("#.*\\n", "");
		tkb.applyRules(ontologyFactory.parseRules(ruleText));

		KBObject template = templates.get(0);
		KBObject invalidProp = tkb.getProperty(ns + "isInvalid");
		KBObject isInvalid = tkb.getPropertyValue(template, invalidProp);

		if (isInvalid != null && (Boolean) isInvalid.getValue()) {
			return null;
		}

		return this;
	}

	public void addInputRole(String vid, Role r) {
		inputRoles.put(vid, r);
	}

	public void addOutputRole(String vid, Role r) {
		outputRoles.put(vid, r);
	}

	public void deleteInputRoleForVariable(String vid) {
		inputRoles.remove(vid);
	}

	public void deleteOutputRoleForVariable(String vid) {
		outputRoles.remove(vid);
	}

	public HashMap<String, Role> getInputRoles() {
		return inputRoles;
	}

	public HashMap<String, Role> getOutputRoles() {
		return outputRoles;
	}

}
