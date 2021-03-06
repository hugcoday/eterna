/*
 * Copyright 2009-2015 xinjunli (micromagic@sina.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package self.micromagic.eterna.search.impl;

import java.util.List;

import org.apache.commons.logging.Log;
import self.micromagic.eterna.digester.ConfigurationException;
import self.micromagic.eterna.search.ConditionBuilder;
import self.micromagic.eterna.search.ConditionProperty;
import self.micromagic.eterna.security.PermissionSet;
import self.micromagic.eterna.share.AttributeManager;
import self.micromagic.eterna.share.EternaFactory;
import self.micromagic.eterna.share.Tool;
import self.micromagic.eterna.share.TypeManager;
import self.micromagic.eterna.sql.preparer.ValuePreparer;
import self.micromagic.eterna.sql.preparer.ValuePreparerCreater;
import self.micromagic.util.StringTool;

/**
 * @author micromagic@sina.com
 */
class ConditionPropertyImpl
		implements ConditionProperty
{
	private static final Log log = Tool.log;

	String name;
	String columnName;
	String columnCaption = null;
	boolean ignore = false;
	int columnType;
	String vpcName;
	ValuePreparerCreater vpCreater;
	boolean visible = true;
	String inputType;
	String defaultValue;
	String listName;
	String defaultBuilderName;
	String permissions;
	PermissionSet permissionSet = null;
	List conditionBuilderList;
	boolean useDefaultConditionBuilder = false;
	ConditionBuilder defaultBuilder;
	AttributeManager attributes;

	public void initialize(EternaFactory factory)
			throws ConfigurationException
	{
		if (this.listName != null)
		{
			this.conditionBuilderList = factory.getConditionBuilderList(this.listName);
			if (this.conditionBuilderList == null)
			{
				log.warn("The ConditionBuilder list [" + this.listName + "] not found.");
			}
		}

		if (this.defaultBuilderName != null)
		{
			this.defaultBuilder = factory.getConditionBuilder(this.defaultBuilderName);
			if (this.defaultBuilder == null)
			{
				log.warn("The ConditionBuilder [" + this.defaultBuilderName + "] not found.");
			}
		}
		else if (this.conditionBuilderList != null)
		{
			if (this.conditionBuilderList.size() > 0)
			{
				this.defaultBuilder = (ConditionBuilder) this.conditionBuilderList.get(0);
			}
		}

		if (this.permissions != null && this.permissions.trim().length() > 0)
		{
			this.permissionSet = new PermissionSet(
					StringTool.separateString(this.permissions, ",", true));
			this.permissionSet.initialize(factory);
		}

		this.vpCreater = factory.createValuePreparerCreater(this.vpcName, this.getColumnPureType());
		if (this.vpCreater == null)
		{
			log.warn("The value preparer generator [" + this.vpcName + "] not found.");
			this.vpCreater = factory.createValuePreparerCreater(this.getColumnPureType());
		}

		if (this.columnCaption == null)
		{
			this.columnCaption = Tool.translateCaption(factory, this.getName());
		}
	}

	public String getName()
	{
		return this.name;
	}

	public String getColumnName()
	{
		return this.columnName;
	}

	public String getColumnCaption()
	{
		return this.columnCaption;
	}

	public String getColumnTypeName()
	{
		return TypeManager.getTypeName(this.columnType);
	}

	public int getColumnPureType()
	{
		return TypeManager.getPureType(this.columnType);
	}

	public int getColumnType()
	{
		return this.columnType;
	}

	public ValuePreparer createValuePreparer(String value)
			throws ConfigurationException
	{
		return this.vpCreater.createPreparer(value);
	}

	public ValuePreparer createValuePreparer(Object value)
			throws ConfigurationException
	{
		return this.vpCreater.createPreparer(value);
	}

	public boolean isIgnore()
	{
		return this.ignore;
	}

	public boolean isVisible()
	{
		return this.visible;
	}

	public String getConditionInputType()
	{
		return this.inputType;
	}

	public String getDefaultValue()
	{
		return this.defaultValue;
	}

	public String getAttribute(String name)
	{
		return (String) this.attributes.getAttribute(name);
	}

	public String[] getAttributeNames()
	{
		return this.attributes.getAttributeNames();
	}

	public PermissionSet getPermissionSet()
	{
		return this.permissionSet;
	}

	public String getConditionBuilderListName()
	{
		return this.listName;
	}

	public boolean isUseDefaultConditionBuilder()
	{
		return this.useDefaultConditionBuilder;
	}

	public ConditionBuilder getDefaultConditionBuilder()
	{
		return this.defaultBuilder;
	}

	public List getConditionBuilderList()
	{
		return this.conditionBuilderList;
	}

}