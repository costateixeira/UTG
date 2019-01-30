package org.hl7.fhir.utg.v2;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.hl7.fhir.r4.formats.IParser.OutputStyle;
import org.hl7.fhir.r4.formats.XmlParser;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.CodeSystem.CodeSystemContentMode;
import org.hl7.fhir.r4.model.CodeSystem.CodeSystemHierarchyMeaning;
import org.hl7.fhir.r4.model.CodeSystem.ConceptDefinitionComponent;
import org.hl7.fhir.r4.model.CodeSystem.PropertyType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TemporalPrecisionEnum;
import org.hl7.fhir.r4.model.ValueSet;
import org.hl7.fhir.r4.model.ValueSet.ConceptReferenceComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetComposeComponent;
import org.hl7.fhir.r4.utils.ToolingExtensions;
import org.hl7.fhir.utg.BaseGenerator;
import org.hl7.fhir.utg.fhir.ListResourceExt;
import org.hl7.fhir.utilities.FolderNameConstants;
import org.hl7.fhir.utilities.Utilities;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class V2SourceGenerator extends BaseGenerator {

	private static final String MASTER_VERSION = "2.9";

	private static final String INTERNAL_CS_OID_PREFIX = "2.16.840.1.113883.18.";
	private static final List<String> VS_ONLY_CS_ID_LIST = Collections
			.unmodifiableList(Arrays.asList("0338", "0125", "0136", "0458", "0459", "0567", "0568", "0929", "0930"));

	public V2SourceGenerator(String dest, Map<String, CodeSystem> csmap, Set<String> knownCS) {
		super(dest, csmap, knownCS);
	}

	private Connection source;
	private Map<String, ObjectInfo> objects = new HashMap<String, ObjectInfo>();
	private Date currentVersionDate;
	private Map<String, Table> tables = new HashMap<String, Table>();
	private Map<String, VersionInfo> versions = new HashMap<String, VersionInfo>();

	private static class V2ConceptIdSequence {
		private static int nextConceptId = 100;
		public static int getNextConceptId() {
			return nextConceptId++;
		}
		public static String getNextConceptIdString() {
			return Integer.toString(getNextConceptId());
		}
		
	}
	
	public class TableEntryComparator implements Comparator<TableEntry> {

		@Override
		public int compare(TableEntry arg0, TableEntry arg1) {
			return arg0.sortNo - arg1.sortNo;
		}
	}

	public enum V2ConceptStatus {
		
		ACTIVE		("A", "Active", 						"0"),
		DEPRECATED	("D", "Deprecated", 					"1"),
		RETIRED		("R", "Retired",						"2"),
		NEW			("N", "New in this Release", 			"3"),
		BACKWARD	("B", "Backwards Compatible Use Only", 	"4");
		
		private String code;
		private String name;
		private String sourceCode;
		
		private V2ConceptStatus(String code, String name, String sourceCode) {
			this.code = code;
			this.name = name;
			this.sourceCode = sourceCode;
		}
		
		public String toString() 		{ return this.getCode(); }
		public String getCode() 		{ return code; }
		public String getName()			{ return name; }
		public String getSourceCode()	{ return sourceCode; }

		public static V2ConceptStatus getStatusForSourceCode(String sourceCode) {
			V2ConceptStatus rval = null;
			if (sourceCode == null || sourceCode.isEmpty()) {
				rval = ACTIVE;
			} else {
				for (V2ConceptStatus cs : V2ConceptStatus.values()) {
					if (sourceCode.equalsIgnoreCase(cs.getSourceCode())) {
						rval = cs;
						break;
					}
				}
			}
			if (rval == null) {
				throw new Error("Unknown concept active code '" + sourceCode + "'");
			}
			return rval; 
		}
	}
	
	public class TableEntry {
		private String code;
		private String display;
		private Map<String, String> langs = new HashMap<String, String>();
		private String comments;
		private int sortNo;
		private String first;
		private String last;
		public String status;
		public boolean backwardsCompatible;

		public boolean hasLang(String code) {
			return langs.containsKey(code);
		}

		public TableEntry copy() {
			TableEntry result = new TableEntry();
			result.code = code;
			result.display = display;
			result.langs.putAll(langs);
			result.comments = comments;
			result.sortNo = sortNo;
			result.status = this.status;
			result.backwardsCompatible = this.backwardsCompatible;
			return result;
		}

		public String getFirst() {
			return first;
		}

		public void setFirst(String first) {
			this.first = first;
		}

		public String getLast() {
			return last;
		}

		public void setLast(String last) {
			this.last = last;
		}

	}

	public class TableVersion {
		
		public static final String VS_EXPANSION_ALL = "1"; 
		public static final String VS_EXPANSION_ENUMERATED = "2"; 
		
		private String version;
		private String name;
		private String csoid;
		private String csversion;
		private String vsoid;

		private String description;
		private int type;
		private boolean generate;
		private String section;
		private String anchor;
		private boolean caseInsensitive;
		private String steward;
		private String conceptDomainRef;
		private String objectDescription;
		private String whereUsed;
		private String v2CodeTableComment;
		private String binding;
		private String versionIntroduced;
		private String vsExpansion;
		private String vocabDomain;
		private String comment;
		private boolean noCodeSystem;

		private List<TableEntry> entries = new ArrayList<TableEntry>();

		public TableVersion(String version, String name) {
			this.version = version;
			this.name = name;
		}

		public boolean hasLang(String code) {
			for (TableEntry v : entries)
				if (v.hasLang(code))
					return true;
			return false;
		}

		public void sort() {
			Collections.sort(entries, new TableEntryComparator());
		}

		public TableEntry find(String code) {
			for (TableEntry t : entries) {
				if (t.code.equals(code))
					return t;
			}
			return null;
		}

		public String getCsoid() {
			return csoid;
		}

		public void setCsoid(String csoid) {
			this.csoid = csoid;
		}

		public String getCsversion() {
			return csversion;
		}

		public void setCsversion(String csversion) {
			this.csversion = csversion;
		}

		public String getVsoid() {
			return vsoid;
		}

		public void setVsoid(String vsoid) {
			this.vsoid = vsoid;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String string) throws SQLException {
			this.description = string;
		}

		public int getType() {
			return type;
		}

		public void setType(int type) {
			this.type = type;
		}

		public boolean isGenerate() {
			return generate;
		}

		public void setGenerate(boolean generate) {
			this.generate = generate;
		}

		public String getSection() {
			return section;
		}

		public void setSection(String section) {
			this.section = section;
		}

		public String getAnchor() {
			return anchor;
		}

		public void setAnchor(String anchor) {
			this.anchor = anchor;
		}

		public boolean isCaseInsensitive() {
			return caseInsensitive;
		}

		public void setCaseInsensitive(boolean caseInsensitive) {
			this.caseInsensitive = caseInsensitive;
		}

		public String getSteward() {
			return steward;
		}

		public void setSteward(String steward) {
			this.steward = steward;
		}

		public String getConceptDomainRef() {
			return conceptDomainRef;
		}

		public void setConceptDomainRef(String conceptDomainRef) {
			this.conceptDomainRef = conceptDomainRef;
		}

		public String getObjectDescription() {
			return objectDescription;
		}

		public void setObjectDescription(String objectDescription) {
			this.objectDescription = objectDescription;
		}

		public String getWhereUsed() {
			return whereUsed;
		}

		public void setWhereUsed(String whereUsed) {
			this.whereUsed = whereUsed;
		}

		public String getV2CodeTableComment() {
			return v2CodeTableComment;
		}

		public void setV2CodeTableComment(String v2CodeTableComment) {
			this.v2CodeTableComment = v2CodeTableComment;
		}

		public String getBinding() {
			return binding;
		}

		public void setBinding(String binding) {
			this.binding = binding;
		}

		public String getVersionIntroduced() {
			return versionIntroduced;
		}

		public void setVersionIntroduced(String versionIntroduced) {
			this.versionIntroduced = versionIntroduced;
		}

		public String getVsExpansion() {
			return vsExpansion;
		}

		public void setVsExpansion(String cld) {
			this.vsExpansion = cld;
		}

		public boolean isValueSetEnumerated() {
			return VS_EXPANSION_ENUMERATED.equalsIgnoreCase(getVsExpansion());
		}
		
		public String getVocabDomain() {
			return vocabDomain;
		}

		public void setVocabDomain(String vocabDomain) {
			this.vocabDomain = vocabDomain;
		}

		public String getComment() {
			return comment;
		}

		public void setComment(String comment) {
			this.comment = comment;
		}

		public boolean isNoCodeSystem() {
			return noCodeSystem;
		}

		public void setNoCodeSystem(boolean noCodeSystem) {
			this.noCodeSystem = noCodeSystem;
		}
	}

	public class Table {
		private String id;
		private Map<String, String> langs = new HashMap<String, String>();
		private String name;
		private String oid;
		private Map<String, TableVersion> versions = new HashMap<String, TableVersion>();
		private TableVersion master;

		public Table(String tableid) {
			id = tableid;
			if (id.length() != 4)
				throw new Error("TableId wrong length " + tableid);
		}

		public String getlang(String code) {
			return langs.get(code);
		}

		public void addLang(String code, String display) {
			if (!Utilities.noString(display))
				langs.put(code, display);
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getOid() {
			return oid;
		}

		public void setOid(String oid) {
			this.oid = oid;
		}

		public void item(String version, String code, String display, String german, String table_name, String comments,
				int sno, String status, boolean backwardsCompatible) {
			if (!versions.containsKey(version))
				versions.put(version, new TableVersion(version, table_name));
			TableEntry entry = new TableEntry();
			entry.code = code;
			entry.display = display;
			if (!Utilities.noString(german))
				entry.langs.put("de", german);
			entry.comments = comments;
			entry.sortNo = sno;
			entry.status = status;
			entry.backwardsCompatible = backwardsCompatible;
			versions.get(version).entries.add(entry);
		}

		public boolean hasLangName(String code) {
			return langs.containsKey(code);
		}

		public boolean hasLangCode(String code) {
			for (TableVersion v : versions.values())
				if (v.hasLang(code))
					return true;
			return false;
		}

		public void processVersions() {
			master = new TableVersion(null, name);
			// first pass, languages
			for (String n : sorted(versions.keySet())) {
				if (n.contains(" ")) {
					String[] parts = n.split("\\ ");
					String lang = translateAffiliateCode(parts[1].toLowerCase());
					TableVersion tvl = versions.get(n);
					TableVersion tv = versions.get(parts[0]);
					if (name == null || !name.equals(tvl.name))
						langs.put(lang, tvl.name);
					if (tv != null) {
						for (TableEntry tel : tvl.entries) {
							TableEntry tem = tv.find(tel.code);
							if (tem == null)
								System.out
										.println("additional code for " + lang + " for table " + id + ": " + tel.code);
							if (tem != null && !tel.display.equals(tem.display))
								tem.langs.put(lang, tel.display);
						}
					} else
						System.out.println("no table for " + n + " for " + id);
				}

			}

			// second pass, versions
			for (String n : sorted(versions.keySet())) {
				//if (this.id.equalsIgnoreCase("0104") && n.equals("2.9")) {
				//	System.out.println("stop");
				//}
				
				if (!n.contains(" ")) {
					TableVersion tv = versions.get(n);
					master.version = tv.version;
					master.vsoid = tv.vsoid;
					master.csoid = tv.csoid;
					master.csversion = tv.csversion;
					master.description = tv.description;
					master.name = tv.name;
					master.steward = tv.steward;
					master.section = tv.section;
					master.anchor = tv.anchor;
					master.generate = tv.generate;
					master.type = tv.type;
					master.objectDescription = tv.objectDescription;
					master.whereUsed = tv.whereUsed;
					master.v2CodeTableComment = tv.v2CodeTableComment;
					master.binding = tv.binding;
					master.versionIntroduced = tv.versionIntroduced;
					master.vsExpansion = tv.vsExpansion;
					master.vocabDomain = tv.vocabDomain;
					master.comment = tv.comment;
					master.noCodeSystem = tv.noCodeSystem;
					for (TableEntry te : tv.entries) {
						TableEntry tem = master.find(te.code);
						if (tem == null) {
							TableEntry ten = te.copy();
							ten.setFirst(n);
							master.entries.add(ten);
						} else {
							if (!Utilities.noString(te.display))
								tem.display = te.display;
							if (!Utilities.noString(te.comments))
								tem.comments = te.comments;
							for (String c : te.langs.keySet())
								tem.langs.put(c, te.langs.get(c));
							tem.sortNo = te.sortNo;
							tem.status = te.status;
							tem.backwardsCompatible = te.backwardsCompatible;
							tem.setLast(null);
						}
					}
					for (TableEntry tem : master.entries) {
						TableEntry te = tv.find(tem.code);
						if (te == null)
							tem.setLast(n);
					}
				}
			}
			master.sort();

			// Save cs_oid and cs_uri pair, but only if this is not a value set only table
			if (!this.isValueSetOnlyTable()) {
				if (!master.noCodeSystem && !Utilities.noString(master.csoid)) {
					ObjectInfo oi = objects.get(master.csoid);
					if (oi != null) {
						oi.setUri("http://terminology.hl7.org/CodeSystem/v2-" + this.id);
					}
				}
			}
		}

		private String translateAffiliateCode(String code) {
			if (code.equals("de"))
				return "de";
			if (code.equals("uk"))
				return "en-UK";
			if (code.equals("fr"))
				return "fr";
			if (code.equals("ch"))
				return "rm";
			if (code.equals("at"))
				return "de-AT";

			throw new Error("No translation for " + code);
		}

		public Set<String> getOids() {
			Set<String> res = new HashSet<String>();
			for (TableVersion tv : versions.values())
				if (tv.csoid != null)
					res.add(tv.csoid);
			return res;
		}

		public TableVersion lastVersionForOid(String oid) {
			TableVersion res = null;
			for (String n : sorted(versions.keySet())) {
				if (oid.equals(versions.get(n).csoid))
					res = versions.get(n);
			}
			if (oid.equals(master.csoid))
				res = master;
			return res;
		}
		
		public boolean isValueSetOnlyTable() {
			return VS_ONLY_CS_ID_LIST.contains(this.id);
		}
		
		public boolean isInternalCsOid() {
			String csOid = this.master.getCsoid();
			return csOid == null || csOid.startsWith(INTERNAL_CS_OID_PREFIX);
		}
	}

	public class ObjectInfo {
		private String oid;
		private String display;
		private String description;
		private int type;
		private String uri;

		public ObjectInfo(String oid, String display, String description, int type) {
			super();
			this.oid = oid;
			this.display = display;
			this.description = description;
			this.type = type;
		}

		public String getOid() {
			return oid;
		}

		public String getDisplay() {
			return display;
		}

		public String getDescription() {
			return description;
		}

		public int getType() {
			return type;
		}

		public String getUri() {
			return uri;
		}

		public void setUri(String uri) {
			this.uri = uri;
		}
	}

	public class VersionInfo {
		private String version;
		private Date publicationDate;

		public VersionInfo(String version, Date publicationDate) {
			super();
			this.version = version;
			this.publicationDate = publicationDate;
		}

		public String getVersion() {
			return version;
		}

		public Date getPublicationDate() {
			return publicationDate;
		}
	}

	public void load(String v2source) throws SQLException {
		System.out.println("loading v2 Source");
		this.source = DriverManager.getConnection("jdbc:ucanaccess://" + v2source); // need ucanaccess from
																					// http://ucanaccess.sourceforge.net/site.html
	}

	public void loadTables() throws IOException, SQLException {
		System.out.println("reading v2 Database");
		Statement stmt = source.createStatement();
		String sql = "Select oid, symbolicName, object_description, Object_type from HL7Objects";
		ResultSet query = stmt.executeQuery(sql);
		while (query.next()) {

			ObjectInfo oi = new ObjectInfo(query.getString("oid"), query.getString("symbolicName"),
					query.getString("object_description"), query.getInt("Object_type"));
			objects.put(oi.getOid(), oi);
		}

		Map<String, VersionInfo> vers = new HashMap<String, VersionInfo>();
		query = stmt.executeQuery("SELECT version_id, hl7_version, date_release from HL7Versions");
		while (query.next()) {
			String vid = Integer.toString(query.getInt("version_id"));
			String dn = query.getString("hl7_version");
			Date pd = query.getDate("date_release");
			if (pd != null && (currentVersionDate == null || currentVersionDate.before(pd)))
				currentVersionDate = pd;
			vers.put(vid, new VersionInfo(dn, pd));
		}

		Map<String, String> nameCache = new HashMap<String, String>();
		query = stmt.executeQuery(
				"SELECT t.table_id, t.version_id, t.display_name, t.oid_table, t.cs_oid, t.cs_version, t.vs_oid, t.vs_expansion, t.vocab_domain, t.interpretation, "
						+ "t.description_as_pub, t.table_type, t.generate, t.section, t.anchor, t.case_insensitive, t.steward, t.where_used, t.v2codetablecomment, t.binding, t.vs_expansion, "
						+ "t.vocab_domain, t.comment, o.object_description, v.hl7_version " + "FROM ((HL7Tables t "
						+ "INNER JOIN HL7Objects o ON t.oid_table = o.oid) "
						+ "INNER JOIN HL7Versions v ON t.version_introduced = v.version_id) "
						+ "WHERE t.version_id < 100 order by t.version_id");

		while (query.next()) {
			String tid = Utilities.padLeft(Integer.toString(query.getInt("table_id")), '0', 4);
			String vid = vers.get(Integer.toString(query.getInt("version_id"))).getVersion();
			String dn = query.getString("display_name");
			nameCache.put(tid + "/" + vid, dn);
			if (!tables.containsKey(tid))
				tables.put(tid, new Table(tid));
			Table t = tables.get(tid);
			t.setName(query.getString("display_name"));
			if (!Utilities.noString(query.getString("interpretation")))
				t.addLang("de", query.getString("interpretation"));
			if (!Utilities.noString(query.getString("oid_table"))) {
				t.setOid(query.getString("oid_table"));
			}
			TableVersion tv = t.versions.get(vid);
			if (tv == null) {
				tv = new TableVersion(vid, query.getString("display_name"));
				t.versions.put(vid, tv);
			}
			if (!Utilities.noString(query.getString("vocab_domain")))
				tv.setConceptDomainRef(query.getString("vocab_domain"));
			tv.setCsoid(query.getString("cs_oid"));
			tv.setCsversion(query.getString("cs_version"));
			tv.setVsoid(query.getString("vs_oid"));
			tv.setDescription(query.getString("description_as_pub"));
			tv.setType(query.getInt("table_type"));
			tv.setGenerate(query.getBoolean("generate"));
			tv.setSection(query.getString("section"));
			tv.setAnchor(query.getString("anchor"));
			tv.setCaseInsensitive(query.getBoolean("case_insensitive"));
			tv.setSteward(query.getString("steward"));
			tv.setObjectDescription(query.getString("object_description"));
			tv.setWhereUsed(query.getString("where_used"));
			tv.setV2CodeTableComment(query.getString("v2codetablecomment"));
			tv.setBinding(query.getString("binding"));
			tv.setVersionIntroduced(query.getString("hl7_version"));
			tv.setVsExpansion(query.getString("vs_expansion"));
			tv.setVocabDomain(query.getString("vocab_domain"));
			tv.setComment(query.getString("comment"));

			if (!Utilities.noString(tv.comment) && tv.comment.contains("no-cs")) {
				tv.noCodeSystem = true;
			}
		}

		int i = 0;
		query = stmt.executeQuery(
				"SELECT table_id, version_id, sort_no, table_value, display_name, interpretation, comment_as_pub, active, modification  from HL7TableValues where version_id < 100");
		while (query.next()) {
			String tid = Utilities.padLeft(Integer.toString(query.getInt("table_id")), '0', 4);
			VersionInfo vid = vers.get(Integer.toString(query.getInt("version_id")));
			if (!tables.containsKey(tid))
				tables.put(tid, new Table(tid));
			versions.put(vid.getVersion(), vid);
			Short sno = query.getShort("sort_no");
			String code = query.getString("table_value");
			String display = query.getString("display_name");
			String german = query.getString("interpretation");
			String comment = query.getString("comment_as_pub");
			
			//String status = readStatusColumns(query.getString("active"), query.getString("modification"));
			V2ConceptStatus conceptStatus = V2ConceptStatus.getStatusForSourceCode(query.getString("active"));
			
			boolean backwardsCompatible = "4".equals(query.getString("active"));

			tables.get(tid).item(vid.getVersion(), code, display, german, nameCache.get(tid + "/" + vid), comment,
					sno == null ? 0 : sno, conceptStatus.toString(), backwardsCompatible);
			i++;
		}
		System.out.println(Integer.toString(i) + " entries loaded");
		for (Table t : tables.values()) {
			for (TableVersion v : t.versions.values()) {
				v.sort();
			}
		}
	}

//	private String readStatusColumns(String active, String modification) {
//		if (Utilities.noString(active))
//			return null;
//		if ("0".equals(active))
//			return "Active";
//		if ("1".equals(active))
//			return "Deprecated";
//		if ("2".equals(active))
//			return "Retired";
//		if ("3".equals(active))
//			return "Active";
//		if ("4".equals(active))
//			return "Active";
//		return null;
//	}

	public void process() {
		for (String n : sorted(tables.keySet())) {
			Table t = tables.get(n);
			t.processVersions();
		}
	}

	public int addConceptDomains(CodeSystem cs, Map<String, String> codes) {
		int count = 0;
		for (String n : sorted(tables.keySet())) {
			if (!n.equals("0000")) {
				Table t = tables.get(n);
				TableVersion tv = t.versions.get(MASTER_VERSION);
				if (tv != null) {
					ObjectInfo oi = objects.get(tv.getConceptDomainRef());
					if (oi != null) {
						ConceptDefinitionComponent c = cs.addConcept();
						c.setCode(oi.getDisplay());
						count++;
						String name = t.getName();
						c.setDisplay(name + " (" + t.id + ")");
						c.setDefinition(oi.description);
						if (codes.containsKey(c.getCode())) {
							System.out.println("Name clash for Domain \"" + c.getCode() + ": used on "
									+ codes.get(c.getCode()) + " and on table " + t.id);
							if (codes.get(c.getCode()).equals("v3"))
								c.setCode(c.getCode() + "V2");
							else
								c.setCode(c.getCode() + "SNAFU");
							codes.put(c.getCode(), "table " + t.id);
						} else
							codes.put(c.getCode(), "table " + t.id);
						c.addProperty().setCode("source").setValue(new CodeType("v2"));
					}
				}
			}
		}
		return count;
	}

	public void generateCodeSystems() throws Exception {
		int c = 0;
		int h = 0;

		ListResource csManifest = ListResourceExt.createManifestList("V2 Code System Release Manifest");
		ListResource vsManifest = ListResourceExt.createManifestList("V2 Value Set Release Manifest");

		for (String n : sorted(tables.keySet())) {
			if (!n.equals("0000")) {
				Table t = tables.get(n);
				generateCodeSystem(t, csManifest, vsManifest);
			}
		}
		System.out.println("Saved v2 code systems (" + Integer.toString(c) + " found, with " + Integer.toString(h)
				+ " past versions)");

		saveManifest(csManifest, vsManifest);
	}

	private void saveManifest(ListResource csManifest, ListResource vsManifest) throws Exception {
		if (csManifest != null) {
			new XmlParser().setOutputStyle(OutputStyle.PRETTY).compose(
					new FileOutputStream(Utilities.path(dest, FolderNameConstants.RELEASE, "v2-CodeSystem-Manifest.xml")), csManifest);
			System.out.println("V2 Code System Manifest saved");
		}

		if (vsManifest != null) {
			new XmlParser().setOutputStyle(OutputStyle.PRETTY).compose(
					new FileOutputStream(Utilities.path(dest, FolderNameConstants.RELEASE, "v2-ValueSet-Manifest.xml")), vsManifest);
			System.out.println("V2 Value Set Manifest saved");
		}
	}

	private void saveV2Manifest(Document document) throws Exception {
		TransformerFactory factory = TransformerFactory.newInstance();
		Transformer transformer = factory.newTransformer();
		Result result = new StreamResult(new File(Utilities.path(dest, FolderNameConstants.RELEASE, "v2-Manifest.xml")));
		Source source = new DOMSource(document);
		transformer.transform(source, result);
		System.out.println("V2 Manifest saved");
	}

	public void mergeV2Manifests() throws Exception {
		File codeSystemManifestFile = new File(Utilities.path(dest, FolderNameConstants.RELEASE, "v2-CodeSystem-Manifest.xml"));
		File valueSetSystemManifestFile = new File(Utilities.path(dest, FolderNameConstants.RELEASE, "v2-ValueSet-Manifest.xml"));
		Document doc = merge(codeSystemManifestFile, valueSetSystemManifestFile);
		removeXMLNSAttribute(doc);
		saveV2Manifest(doc);
	}

	private static Document merge(File... files) throws Exception {
		DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
		docBuilderFactory.setIgnoringElementContentWhitespace(true);
		DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
		Document base = docBuilder.parse(files[0]);
		Element rootNode = base.getDocumentElement();

		for (int i = 1; i < files.length; i++) {
			Document merge = docBuilder.parse(files[i]);
			NodeList nextResults = merge.getDocumentElement().getElementsByTagName("entry");

			for (int j = 0; j < nextResults.getLength(); j++) {
				Node kid = nextResults.item(j);
				kid = base.importNode(kid, true);
				rootNode.appendChild(kid);
			}
		}
		return base;
	}

	private static Document removeXMLNSAttribute(Document doc)
			throws ParserConfigurationException, SAXException, IOException {
		NodeList nodeList = doc.getElementsByTagName("*");
		for (int i = 0; i < nodeList.getLength(); i++) {
			if (nodeList.item(i).getNodeType() == Node.ELEMENT_NODE) {
				Element ele = (Element) nodeList.item(i);
				NamedNodeMap nnm = ele.getAttributes();
				for (int a = nnm.getLength() - 1; a >= 0; a--) { // back to front because of remove in loop!
					Attr attr = (Attr) nnm.item(a);
					if (attr.getNodeName().startsWith("xmlns")) {
						ele.removeAttributeNode(attr);
					}
				}
			}
		}
		return doc;
	}

	private void generateCodeSystem(Table t, ListResource csManifest, ListResource vsManifest)
			throws FileNotFoundException, IOException {
		TableVersion tv = t.master;

		if (tv.noCodeSystem)
			return;

		CodeSystem cs = new CodeSystem();

		cs.setId("v2-" + t.id);
		cs.setVersion(tv.csversion);

		ObjectInfo oi = objects.get(tv.csoid);
		if (oi != null) {
			cs.setUrl(oi.uri);
			cs.setName(Utilities.capitalize(oi.display));
			// cs.setName(oi.display);
		} else {
			cs.setUrl("http://terminology.hl7.org/CodeSystem/" + cs.getId());
			cs.setName("V2Table" + t.id);
		}

		// knownCS is a HashSet
		knownCS.add(cs.getUrl());

		cs.setValueSet("http://terminology.hl7.org/ValueSet/" + cs.getId());
		cs.setTitle("V2 Table Code System: " + t.name);
		cs.setStatus(PublicationStatus.ACTIVE);
		cs.setExperimental(false);
		if (tv.csoid != null) {
			cs.getIdentifier().setSystem("urn:ietf:rfc:3986").setValue("urn:oid:" + tv.csoid);
		}
		cs.setDateElement(new DateTimeType(currentVersionDate, TemporalPrecisionEnum.DAY));
		cs.setPublisher("HL7, Inc");
		cs.addContact().addTelecom().setSystem(ContactPointSystem.URL).setValue("http://www.hl7.org/");
		if (tv.csoid != null && objects.containsKey(tv.csoid))
			cs.setDescription(objects.get(tv.csoid).description);
		else if (!Utilities.noString(tv.description))
			cs.setDescription(tv.description);
		else
			cs.setDescription("Underlying Master Code System for V2 table " + t.id + " (" + t.name + ")");
		cs.setPurpose("Underlying Master Code System for V2 table " + t.id + " (" + t.name + ")");
		cs.setCopyright("Copyright HL7. Licensed under creative commons public domain");
		if (tv.isCaseInsensitive())
			cs.setCaseSensitive(false);
		else
			cs.setCaseSensitive(true);
		cs.setHierarchyMeaning(CodeSystemHierarchyMeaning.ISA); // todo - is this correct
		cs.setCompositional(false);
		cs.setVersionNeeded(false);
		cs.setContent(CodeSystemContentMode.COMPLETE);
		if (!Utilities.noString(tv.getSteward()))
			cs.getExtension()
					.add(new Extension().setUrl("http://hl7.org/fhir/StructureDefinition/structuredefinition-wg")
							.setValue(new CodeType(tv.getSteward())));
		if (tv.isGenerate())
			cs.getExtension()
					.add(new Extension()
							.setUrl("http://healthintersections.com.au/fhir/StructureDefinition/valueset-generate")
							.setValue(new BooleanType(true)));

		cs.addProperty().setCode("status").setUri("http://terminology.hl7.org/csprop/status").setType(PropertyType.CODE)
				.setDescription("Status of the concept");
		//cs.addProperty().setCode("intro").setUri("http://terminology.hl7.org/csprop/intro").setType(PropertyType.CODE)
		//		.setDescription("Version of HL7 in which the code was first defined");
		cs.addProperty().setCode("deprecated").setUri("http://terminology.hl7.org/csprop/deprecated")
				.setType(PropertyType.CODE).setDescription("Version of HL7 in which the code was deprecated");
		//cs.addProperty().setCode("backwardsCompatible").setUri("http://terminology.hl7.org/csprop/backwardsCompatible")
		//		.setType(PropertyType.BOOLEAN)
		//		.setDescription("Whether code is considered 'backwards compatible' (whatever that means)");

		for (TableEntry te : tv.entries) {
			ConceptDefinitionComponent c = cs.addConcept();
			c.setCode(te.code);
			String name = te.display;
			c.setDisplay(name);
			c.setDefinition(name);
			// Use sequence for concept id, not sort_no
			//c.setId(Integer.toString(te.sortNo));
			c.setId(V2ConceptIdSequence.getNextConceptIdString());
			if (!Utilities.noString(te.comments))
				ToolingExtensions.addCSComment(c, te.comments);
			//if (te.getFirst() != null)
			//	c.addProperty().setCode("intro").setValue(new CodeType(te.getFirst()));
			if (!Utilities.noString(te.getLast()))
				c.addProperty().setCode("deprecated").setValue(new CodeType(te.getLast()));
			if (!Utilities.noString(te.status))
				c.addProperty().setCode("status").setValue(new CodeType(te.status));
			//if (te.backwardsCompatible)
			//	c.addProperty().setCode("backwardsCompatible").setValue(new BooleanType(te.backwardsCompatible));
		}

		ValueSet vs = produceValueSet("Master", cs, t, tv);


		// Only write code systems if not value set only and cs oid starts with prefix, per Ted
		if (!t.isValueSetOnlyTable() && t.isInternalCsOid()) {
			new XmlParser().setOutputStyle(OutputStyle.PRETTY).compose(
					new FileOutputStream(Utilities.path(dest, FolderNameConstants.V2, FolderNameConstants.CODESYSTEMS, "cs-" + cs.getId()) + ".xml"), cs);
		}

		new XmlParser().setOutputStyle(OutputStyle.PRETTY).compose(
				new FileOutputStream(Utilities.path(dest, FolderNameConstants.V2, FolderNameConstants.VALUESETS, "vs-" + cs.getId()) + ".xml"), vs);

		csManifest.addEntry(ListResourceExt.createCodeSystemListEntry(cs, (String) null));
		vsManifest.addEntry(ListResourceExt.createValueSetListEntry(vs, (String) null));
	}

	// private void generateVersionCodeSystem(Table t, TableVersion tv, ListResource
	// csManifest, ListResource vsManifest) throws FileNotFoundException,
	// IOException {
	// CodeSystem cs = new CodeSystem();
	// cs.setId("v2-"+t.id+"-"+tv.version);
	// cs.setUrl("http://terminology.hl7.org/CodeSystem/"+cs.getId());
	// knownCS.add(cs.getUrl());
	// cs.setValueSet("http://terminology.hl7.org/ValueSet/"+cs.getId());
	//
	// cs.setVersion(tv.csversion);
	// cs.setName("V2Table"+t.id+"v"+tv.version);
	// cs.setTitle("V2 Table: "+t.name);
	// cs.setStatus(PublicationStatus.ACTIVE);
	// cs.setExperimental(false);
	// cs.getIdentifier().setSystem("urn:ietf:rfc:3986").setValue("urn:oid:"+tv.csoid);
	// cs.setDateElement(new DateTimeType(currentVersionDate,
	// TemporalPrecisionEnum.DAY));
	// cs.setPublisher("HL7, Inc");
	// cs.addContact().addTelecom().setSystem(ContactPointSystem.URL).setValue("https://github.com/HL7/UTG");
	// if (tv.csoid != null && objects.containsKey(tv.csoid))
	// cs.setDescription(objects.get(tv.csoid).description);
	// else if (!Utilities.noString(tv.description))
	// cs.setDescription(tv.description);
	// else
	// cs.setDescription("Underlying Code System for V2 table "+t.id+" ("+t.name+"
	// "+tv.version+")");
	// cs.setPurpose("Underlying Code System for V2 table "+t.id+" ("+t.name+",
	// version "+tv.version+")");
	// cs.setCopyright("Copyright HL7. Licensed under creative commons public
	// domain");
	// if (tv.isCaseInsensitive())
	// cs.setCaseSensitive(false);
	// else
	// cs.setCaseSensitive(true); // not that it matters, since they are all numeric
	// cs.setHierarchyMeaning(CodeSystemHierarchyMeaning.ISA); // todo - is this
	// correct
	// cs.setCompositional(false);
	// cs.setVersionNeeded(false);
	// cs.setContent(CodeSystemContentMode.COMPLETE);
	// if (!Utilities.noString(tv.getSteward()))
	// cs.getExtension().add(new
	// Extension().setUrl("http://hl7.org/fhir/StructureDefinition/structuredefinition-wg").setValue(new
	// CodeType(tv.getSteward())));
	//// if (!Utilities.noString(tv.getAnchor()))
	//// cs.getExtension().add(new
	// Extension().setUrl("http://healthintersections.com.au/fhir/StructureDefinition/valueset-stdref").setValue(new
	// UriType("http://hl7.org/v2/"+tv.getAnchor())));
	//// if (!Utilities.noString(tv.getSection()))
	//// cs.getExtension().add(new
	// Extension().setUrl("http://healthintersections.com.au/fhir/StructureDefinition/valueset-stdsection").setValue(new
	// StringType(tv.getSection())));
	// if (tv.getType() > 0)
	// cs.getExtension().add(new
	// Extension().setUrl("http://healthintersections.com.au/fhir/StructureDefinition/valueset-v2type").setValue(new
	// CodeType(codeForType(tv.getType()))));
	// if (tv.isGenerate())
	// cs.getExtension().add(new
	// Extension().setUrl("http://healthintersections.com.au/fhir/StructureDefinition/valueset-generate").setValue(new
	// BooleanType(true)));
	//
	// cs.addProperty().setCode("status").setUri("http://terminology.hl7.org/csprop/status").setType(PropertyType.CODE).setDescription("Status
	// of the concept");
	// cs.addProperty().setCode("intro").setUri("http://terminology.hl7.org/csprop/intro").setType(PropertyType.CODE).setDescription("Version
	// of HL7 in which the code was first defined");
	// cs.addProperty().setCode("deprecated").setUri("http://terminology.hl7.org/csprop/deprecated").setType(PropertyType.CODE).setDescription("Version
	// of HL7 in which the code was deprecated");
	// cs.addProperty().setCode("backwardsCompatible").setUri("http://terminology.hl7.org/csprop/backwardsCompatible").setType(PropertyType.BOOLEAN).setDescription("Whether
	// code is considered 'backwards compatible' (whatever that means)");
	//
	// for (TableEntry te : tv.entries) {
	// ConceptDefinitionComponent c = cs.addConcept();
	// c.setCode(te.code);
	// String name = te.display;
	// c.setDisplay(name);
	// c.setDefinition(name);
	// c.setId(Integer.toString(te.sortNo));
	// if (!Utilities.noString(te.comments))
	// ToolingExtensions.addCSComment(c, te.comments);
	// if (te.getFirst() != null)
	// c.addProperty().setCode("intro").setValue(new CodeType(te.getFirst()));
	// if (!Utilities.noString(te.getLast()))
	// c.addProperty().setCode("deprecated").setValue(new CodeType(te.getLast()));
	// if (!Utilities.noString(te.status))
	// c.addProperty().setCode("status").setValue(new CodeType(te.status));
	// if (te.backwardsCompatible)
	// c.addProperty().setCode("backwardsCompatible").setValue(new
	// BooleanType(te.backwardsCompatible));
	// }
	//
	// ValueSet vs = produceValueSet("Master", cs, t, tv);
	// new XmlParser().setOutputStyle(OutputStyle.PRETTY).compose(new
	// FileOutputStream(Utilities.path(dest, "v2", "codeSystems", "v"+tv.version,
	// "cs-"+cs.getId())+".xml"), cs);
	// new XmlParser().setOutputStyle(OutputStyle.PRETTY).compose(new
	// FileOutputStream(Utilities.path(dest, "v2", "valueSets", "v"+tv.version,
	// "vs-"+cs.getId())+".xml"), vs);
	//
	// csManifest.addEntry(ListResourceExt.createCodeSystemListEntry(cs,
	// (String)null));
	// vsManifest.addEntry(ListResourceExt.createValueSetListEntry(vs,
	// (String)null));
	// }

	private String codeForType(int type) {
		if (type == 0)
			return "undefined";
		if (type == 1)
			return "User";
		if (type == 2)
			return "HL7";
		if (type == 4)
			return "no longer used";
		if (type == 5)
			return "replaced";
		if (type == 6)
			return "User Group /National Defined";
		if (type == 7)
			return "Imported";
		if (type == 8)
			return "Externally defined";
		if (type == 9)
			return "HL7-EXT";

		throw new Error("not done yet: " + Integer.toString(type));
	}

	private ValueSet produceValueSet(String vid, CodeSystem cs, Table t, TableVersion tv) {
		ValueSet vs = new ValueSet();
		vs.setId(cs.getId());
		vs.setUrl("http://terminology.hl7.org/ValueSet/" + vs.getId());
		// Set all value set versions to 1, per Ted
		vs.setVersion("1");
		if (tv.vsoid != null) {
			vs.setName(Utilities.capitalize(objects.get(tv.vsoid).display));
			vs.setTitle(objects.get(tv.vsoid).display);
		} else {
			vs.setName("V2Table" + t.id + "Version" + vid);
			vs.setTitle("V2 Table " + t.id + " Version " + vid);
		}
		vs.setStatus(PublicationStatus.ACTIVE);
		vs.setExperimental(false);
		if (tv.vsoid != null)
			vs.addIdentifier().setSystem("urn:ietf:rfc:3986").setValue("urn:oid:" + tv.vsoid);
		vs.setDateElement(new DateTimeType(currentVersionDate, TemporalPrecisionEnum.DAY));
		vs.setPublisher("HL7, Inc");
		vs.addContact().addTelecom().setSystem(ContactPointSystem.URL).setValue("https://github.com/HL7/UTG");
		vs.setDescription("V2 Table " + t.id + " Version " + vid + " (" + t.name + ")");
		vs.setCopyright("Copyright HL7. Licensed under creative commons public domain");

		ValueSetComposeComponent vsCompose = vs.getCompose();
		ConceptSetComponent vsInclude = vsCompose.addInclude();
		vsInclude.setSystem(cs.getUrl()).setVersion(cs.getVersion());

		if (tv.isValueSetEnumerated()) {
			for (TableEntry te : tv.entries) {
				ConceptReferenceComponent c = vsInclude.addConcept();
				c.setCode(te.code);
			}
		}
		
		return vs;
	}

	public void generateTables() throws FileNotFoundException, IOException {
		CodeSystem cs = new CodeSystem();
		cs.setId("v2-tables");
		cs.setUrl("http://hl7.org/terminology.hl7.org/CodeSystem/" + cs.getId());
		cs.setName("V2Tables");
		cs.setTitle("V2 Table List");
		cs.setStatus(PublicationStatus.ACTIVE);
		cs.setExperimental(false);

		cs.setDateElement(new DateTimeType(currentVersionDate, TemporalPrecisionEnum.DAY));
		cs.setPublisher("HL7, Inc");
		cs.addContact().addTelecom().setSystem(ContactPointSystem.URL).setValue("https://github.com/HL7/UTG");
		cs.setDescription("Master List of V2 Tables");
		cs.setCopyright("Copyright HL7. Licensed under creative commons public domain");
		cs.setCaseSensitive(true);
		cs.setHierarchyMeaning(CodeSystemHierarchyMeaning.ISA);
		cs.setCompositional(false);
		cs.setVersionNeeded(false);
		cs.setContent(CodeSystemContentMode.COMPLETE);

		cs.addProperty().setCode("table-oid").setUri("http://terminology.hl7.org/csprop/oid")
				.setType(PropertyType.STRING).setDescription("OID For Table");
		cs.addProperty().setCode("csoid").setUri("http://terminology.hl7.org/csprop/csoid").setType(PropertyType.STRING)
				.setDescription("OID For Code System");
		cs.addProperty().setCode("csoid").setUri("http://terminology.hl7.org/csprop/csuri").setType(PropertyType.STRING)
				.setDescription("URI For Code System");
		cs.addProperty().setCode("vsoid").setUri("http://terminology.hl7.org/csprop/vsoid").setType(PropertyType.STRING)
				.setDescription("OID For Value Set");
		cs.addProperty().setCode("v2type").setUri("http://terminology.hl7.org/csprop/v2type").setType(PropertyType.CODE)
				.setDescription("Type of table");
		cs.addProperty().setCode("generate").setUri("http://terminology.hl7.org/csprop/generate")
				.setType(PropertyType.BOOLEAN).setDescription("whether to generate table");
		cs.addProperty().setCode("version").setUri("http://terminology.hl7.org/csprop/version")
				.setType(PropertyType.INTEGER).setDescription("Business version of table metadata");
		cs.addProperty().setCode("structuredefinition-wg")
				.setUri("http://terminology.hl7.org/csprop/structuredefinition-wg").setType(PropertyType.STRING)
				.setDescription("Steward for the table.");
		cs.addProperty().setCode("where-used").setUri("http://terminology.hl7.org/csprop/where-used")
				.setType(PropertyType.STRING).setDescription("Where this table is used.");
		cs.addProperty().setCode("v2-codes-table-comment")
				.setUri("http://terminology.hl7.org/csprop/v2-codes-table-comment").setType(PropertyType.STRING)
				.setDescription("V2 Codes Table Comment.");
		cs.addProperty().setCode("binding").setUri("http://terminology.hl7.org/csprop/binding")
				.setType(PropertyType.STRING).setDescription("Binding.");
		cs.addProperty().setCode("version-introduced").setUri("http://terminology.hl7.org/csprop/version-introduced")
				.setType(PropertyType.STRING).setDescription("Version Introduced.");
		cs.addProperty().setCode("cld").setUri("http://terminology.hl7.org/csprop/cld").setType(PropertyType.STRING)
				.setDescription("Content Logical Definition.");
		cs.addProperty().setCode("vocab-domain").setUri("http://terminology.hl7.org/csprop/vocab-domain")
				.setType(PropertyType.STRING).setDescription("Vocabulary Domain for this table");

		int count = 0;
		for (String n : sorted(tables.keySet())) {
			if (!n.equals("0000")) {
				Table t = tables.get(n);
				TableVersion tv = t.master;
				if (tv != null) {
					ConceptDefinitionComponent c = cs.addConcept();
					c.setCode(t.id);
					count++;
					c.setDisplay(t.name);
					c.setDefinition(tv.objectDescription);
					c.addProperty().setCode("table-oid").setValue(new StringType(t.oid));
					if (!Utilities.noString(tv.csoid)) {
						c.addProperty().setCode("csoid").setValue(new StringType(tv.csoid));
						ObjectInfo oi = objects.get(tv.csoid);

						if (oi != null) {
							c.addProperty().setCode("csuri").setValue(new StringType(oi.uri));
						}
					}
					if (!Utilities.noString(tv.vsoid))
						c.addProperty().setCode("vsoid").setValue(new StringType(tv.vsoid));
					if (tv.getType() > 0)
						c.addProperty().setCode("v2type").setValue(new CodeType(codeForType(tv.getType())));
					if (tv.isGenerate())
						c.addProperty().setCode("generate").setValue(new BooleanType(true));
					c.addProperty().setCode("version").setValue(new IntegerType(10));
					if (!Utilities.noString(tv.steward))
						c.addProperty().setCode("structuredefinition-wg").setValue(new StringType(tv.steward));
					if (!Utilities.noString(tv.whereUsed))
						c.addProperty().setCode("where-used").setValue(new StringType(tv.whereUsed));
					if (!Utilities.noString(tv.v2CodeTableComment))
						c.addProperty().setCode("v2-codes-table-comment")
								.setValue(new StringType(tv.v2CodeTableComment));
					if (!Utilities.noString(tv.binding))
						c.addProperty().setCode("binding").setValue(new StringType(tv.binding));
					if (!Utilities.noString(tv.versionIntroduced))
						c.addProperty().setCode("version-introduced").setValue(new StringType(tv.versionIntroduced));
					if (!Utilities.noString(tv.versionIntroduced))
						c.addProperty().setCode("cld").setValue(new StringType(tv.vsExpansion));
					if (!Utilities.noString(tv.vocabDomain))
						c.addProperty().setCode("vocab-domain").setValue(new StringType(tv.vocabDomain));
				}
			}
		}

		new XmlParser().setOutputStyle(OutputStyle.PRETTY)
				.compose(new FileOutputStream(Utilities.path(dest, FolderNameConstants.V2, "v2-tables.xml")), cs);
		System.out.println("Save tables (" + Integer.toString(count) + " found)");

	}

}
