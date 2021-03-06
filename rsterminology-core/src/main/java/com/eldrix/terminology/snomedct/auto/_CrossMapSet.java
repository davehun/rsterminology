package com.eldrix.terminology.snomedct.auto;

import java.util.List;

import org.apache.cayenne.CayenneDataObject;
import org.apache.cayenne.exp.Property;

import com.eldrix.terminology.snomedct.CrossMapTable;

/**
 * Class _CrossMapSet was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _CrossMapSet extends CayenneDataObject {

    private static final long serialVersionUID = 1L; 

    public static final String SET_ID_PK_COLUMN = "set_id";

    public static final Property<String> NAME = new Property<String>("name");
    public static final Property<String> REALM_ID = new Property<String>("realmId");
    public static final Property<Integer> RULE_TYPE = new Property<Integer>("ruleType");
    public static final Property<String> SCHEME_ID = new Property<String>("schemeId");
    public static final Property<String> SCHEME_NAME = new Property<String>("schemeName");
    public static final Property<String> SCHEME_VERSION = new Property<String>("schemeVersion");
    public static final Property<String> SEPARATOR = new Property<String>("separator");
    public static final Property<Long> SET_ID = new Property<Long>("setId");
    public static final Property<Integer> TYPE = new Property<Integer>("type");
    public static final Property<List<CrossMapTable>> TABLES = new Property<List<CrossMapTable>>("tables");

    public void setName(String name) {
        writeProperty("name", name);
    }
    public String getName() {
        return (String)readProperty("name");
    }

    public void setRealmId(String realmId) {
        writeProperty("realmId", realmId);
    }
    public String getRealmId() {
        return (String)readProperty("realmId");
    }

    public void setRuleType(Integer ruleType) {
        writeProperty("ruleType", ruleType);
    }
    public Integer getRuleType() {
        return (Integer)readProperty("ruleType");
    }

    public void setSchemeId(String schemeId) {
        writeProperty("schemeId", schemeId);
    }
    public String getSchemeId() {
        return (String)readProperty("schemeId");
    }

    public void setSchemeName(String schemeName) {
        writeProperty("schemeName", schemeName);
    }
    public String getSchemeName() {
        return (String)readProperty("schemeName");
    }

    public void setSchemeVersion(String schemeVersion) {
        writeProperty("schemeVersion", schemeVersion);
    }
    public String getSchemeVersion() {
        return (String)readProperty("schemeVersion");
    }

    public void setSeparator(String separator) {
        writeProperty("separator", separator);
    }
    public String getSeparator() {
        return (String)readProperty("separator");
    }

    public void setSetId(Long setId) {
        writeProperty("setId", setId);
    }
    public Long getSetId() {
        return (Long)readProperty("setId");
    }

    public void setType(Integer type) {
        writeProperty("type", type);
    }
    public Integer getType() {
        return (Integer)readProperty("type");
    }

    public void addToTables(CrossMapTable obj) {
        addToManyTarget("tables", obj, true);
    }
    public void removeFromTables(CrossMapTable obj) {
        removeToManyTarget("tables", obj, true);
    }
    @SuppressWarnings("unchecked")
    public List<CrossMapTable> getTables() {
        return (List<CrossMapTable>)readProperty("tables");
    }


}
