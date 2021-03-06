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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.io.Writer;
import java.io.IOException;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import self.micromagic.eterna.digester.ConfigurationException;
import self.micromagic.eterna.model.AppData;
import self.micromagic.eterna.search.ConditionBuilder;
import self.micromagic.eterna.search.ConditionProperty;
import self.micromagic.eterna.search.SearchAdapter;
import self.micromagic.eterna.search.SearchManager;
import self.micromagic.eterna.search.SearchManagerGenerator;
import self.micromagic.eterna.share.AbstractGenerator;
import self.micromagic.eterna.sql.preparer.PreparerManager;
import self.micromagic.eterna.sql.preparer.ValuePreparer;
import self.micromagic.eterna.view.DataPrinter;
import self.micromagic.util.StringAppender;
import self.micromagic.util.StringTool;

/**
 * @author micromagic@sina.com
 */
public class SearchManagerImpl extends AbstractGenerator
		implements SearchManagerGenerator, SearchManager, DataPrinter.BeanPrinter
{
	static final String SEARCHMANAGER_DEALED_PREFIX = "ETERNA_SEARCHMANAGER_DEALED:";

	private transient Attributes attributes = DEFAULT_PROPERTIES;

	private transient String preparedCondition = "";
	private transient PreparerManager generatedPM = null;
	private transient Map conditionMap = null;
	private List conditionList = null;
	private transient SearchAdapter resetConditionSearch = null;
	private transient Map specialMap = null;

	private int conditionVersion = 1;
	private int pageNum = 0;
	private int pageSize = -1;

	public SearchManagerImpl()
	{
	}

	public int getPageNum()
	{
		return this.pageNum;
	}

	public int getPageSize(int defaultSize)
	{
		return this.pageSize == -1 ? defaultSize : this.pageSize;
	}

	public boolean hasQueryType(AppData data)
	{
		String temp;
		Map raMap = data.getRequestAttributeMap();
		if (raMap.get(FORCE_DEAL_CONDITION) != null)
		{
			temp = this.attributes.queryTypeReset;
		}
		else if (raMap.get(FORCE_CLEAR_CONDITION) != null)
		{
			temp = this.attributes.queryTypeClear;
		}
		else
		{
			temp = data.getRequestParameter(this.attributes.queryTypeTag);
		}
		return temp != null
				&& (this.attributes.queryTypeReset.equals(temp) || this.attributes.queryTypeClear.equals(temp));
	}

	public int getConditionVersion()
	{
		return this.conditionVersion;
	}

	public void setPageNumAndCondition(AppData data, SearchAdapter search)
			throws ConfigurationException
	{
		this.setPageNumAndCondition(data, search, true);
	}

	private void setPageNumAndCondition(AppData data, SearchAdapter search, boolean checkDealed)
			throws ConfigurationException
	{
		Map raMap = data.getRequestAttributeMap();
		if (checkDealed)
		{
			String checkName = SEARCHMANAGER_DEALED_PREFIX + search.getSearchManagerName();
			if (raMap.get(checkName) != null)
			{
				if (raMap.get(FORCE_DEAL_CONDITION) == null && raMap.get(FORCE_CLEAR_CONDITION) == null)
				{
					return;
				}
			}
			raMap.put(checkName, "1");
		}
		try
		{
			this.pageNum = Integer.parseInt(data.getRequestParameter(this.attributes.pageNumTag));
		}
		catch (Exception ex) {}
		try
		{
			int tmpI = Integer.parseInt(data.getRequestParameter(this.attributes.pageSizeTag));
			this.pageSize = tmpI < 0 ? -1 : tmpI > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : tmpI;
		}
		catch (Exception ex) {}
		try
		{
			String temp;
			if (raMap.get(FORCE_DEAL_CONDITION) != null)
			{
				temp = this.attributes.queryTypeReset;
			}
			else if (raMap.get(FORCE_CLEAR_CONDITION) != null)
			{
				temp = this.attributes.queryTypeClear;
			}
			else
			{
				temp = data.getRequestParameter(this.attributes.queryTypeTag);
			}
			if (this.attributes.queryTypeReset.equals(temp))
			{
				this.conditionVersion++;
				this.resetConditionSearch = search;
				String xml = data.getRequestParameter(this.attributes.querySettingTag);
				if (log.isDebugEnabled())
				{
					log.debug("The xml:" + xml);
				}
				boolean saveCondition = raMap.get(SAVE_CONDITION) != null || search.isSpecialCondition();
				this.clearCondition();
				if (xml != null)
				{
					Document doc = DocumentHelper.parseText(xml);
					this.setConditionValues(doc, search, saveCondition);
				}
				else
				{
					this.setConditionValues(data, search, saveCondition, false);
				}
			}
			else if (this.attributes.queryTypeClear.equals(temp))
			{
				this.conditionVersion++;
				this.preparedCondition = null;
				this.generatedPM = null;
				this.clearCondition();
			}
			else if (this.conditionVersion == 1)
			{
				// version为1, 表示是第一次进入且未标记要设置条件
				this.conditionVersion++;
				boolean saveCondition = raMap.get(SAVE_CONDITION) != null || search.isSpecialCondition();
				this.setConditionValues(data, search, saveCondition, true);
			}
		}
		catch (Exception ex)
		{
			log.error("When set condition values.", ex);
		}
	}

	/**
	 * 根据参数的名称从<code>doc</code>中的条件配置，设置查询条件。
	 */
	private void setConditionValues(Document doc, SearchAdapter search, boolean saveCondition)
			throws ConfigurationException
	{
		StringAppender buf = StringTool.createStringAppender(512);

		List preparerList = new LinkedList();
		List groups = doc.getRootElement().element("groups").elements("group");
		Iterator gitr = groups.iterator();
		Element group;
		String value;
		while (gitr.hasNext())
		{
			group = (Element) gitr.next();
			List conditions = group.elements("condition");
			if (conditions.size() > 0)
			{
				Iterator citr = conditions.iterator();
				int index = 0;
				while (citr.hasNext())
				{
					Element condition = (Element) citr.next();
					// 这里不捕获异常, 因为在方法外已经捕获了
					ConditionProperty cp = search.getConditionProperty(
							condition.attributeValue("name"));
					if (cp != null && !cp.isIgnore())
					{
						ConditionBuilder cb = cp.isUseDefaultConditionBuilder() ? cp.getDefaultConditionBuilder()
								: search.getFactory().getConditionBuilder(condition.attributeValue("builder"));
						value = condition.attributeValue("value");
						ConditionBuilder.Condition cbCon = null;
						try
						{
							cbCon = cb.buildeCondition(cp.getColumnName(), value, cp);
							if (saveCondition)
							{
								this.addCondition(new Condition(cp.getName(), group.attributeValue("name"), value, cb));
							}
						}
						catch (Exception ex)
						{
							log.error("Error wen builde condition: ConditionProperty[" + cp.getName()
									+ "], search[" + search.getName() + "]", ex);
						}
						if (log.isDebugEnabled())
						{
							log.debug("OneCondition:" + cbCon != null  ? cbCon.toString() : null);
						}
						if (cbCon != null)
						{
							if (index != 0)
							{
								buf.append(" AND ");
							}
							else
							{
								if (buf.length() > 0)
								{
									buf.append(" OR (");
								}
								else
								{
									buf.append("( (");
								}
							}
							buf.append(cbCon.sqlPart);
							for (int pIndex = 0; pIndex < cbCon.preparers.length; pIndex++)
							{
								cbCon.preparers[pIndex].setName(cp.getName());
							}
							preparerList.addAll(Arrays.asList(cbCon.preparers));
							index++;
						}
					}
				}
				if (index > 0)
				{
					buf.append(')');
				}
			}
		}
		if (buf.length() > 0)
		{
			buf.append(" )");
		}
		this.preparedCondition = buf.toString();
		if (log.isDebugEnabled())
		{
			log.debug("Condition:" + buf.toString());
		}
		int pSize = preparerList.size();
		if (pSize > 0)
		{
			PreparerManager tmpPM = new PreparerManager(pSize);
			Iterator itr = preparerList.iterator();
			for (int i = 0; i < pSize; i++)
			{
				ValuePreparer preparer = (ValuePreparer) itr.next();
				preparer.setRelativeIndex(i + 1);
				tmpPM.setValuePreparer(preparer);
			}
			this.generatedPM = tmpPM;
		}
		else
		{
			this.generatedPM = null;
		}
	}

	/**
	 * 根据参数的名称从<code>request</code>中的读取参数，设置查询条件。
	 */
	private void setConditionValues(AppData data, SearchAdapter search, boolean saveCondition, boolean dealDefault)
			throws ConfigurationException
	{
		StringAppender buf = StringTool.createStringAppender(512);

		List preparerList = new LinkedList();
		int index = 0;
		int count = search.getConditionPropertyCount();
		for (int i = 0; i < count; i++)
		{
			// 这里不捕获异常, 因为在方法外已经捕获了
			ConditionProperty cp = search.getConditionProperty(i);
			String value;
			if (dealDefault)
			{
				value = cp.getDefaultValue();
				if (value != null)
				{
					if (value.startsWith(DATA_DEFAULT_VALUE_PREFIX))
					{
						Object obj = data.dataMap.get(value.substring(DATA_DEFAULT_VALUE_PREFIX.length()));
						if (obj == null)
						{
							value = null;
						}
						else
						{
							value = String.valueOf(obj);
						}
					}
				}
			}
			else
			{
				value = data.getRequestParameter(cp.getName());
			}
			if (!cp.isIgnore() && value != null && value.length() > 0)
			{
				ConditionBuilder cb = cp.getDefaultConditionBuilder();
				ConditionBuilder.Condition cbCon = null;
				try
				{
					cbCon = cb.buildeCondition(cp.getColumnName(), value, cp);
					if (saveCondition)
					{
						this.addCondition(new Condition(cp.getName(), null, value, cb));
					}
				}
				catch (Exception ex)
				{
					log.error("Error wen builde condition: ConditionProperty[" + cp.getName()
							+ "], search[" + search.getName() + "]", ex);
				}
				if (log.isDebugEnabled())
				{
					log.debug("OneCondition:" + cbCon != null  ? cbCon.toString() : null);
				}
				if (cbCon != null)
				{
					if (index != 0)
					{
						buf.append(" AND ");
					}
					else
					{
						buf.append('(');
					}
					buf.append(cbCon.sqlPart);
					for (int pIndex = 0; pIndex < cbCon.preparers.length; pIndex++)
					{
						cbCon.preparers[pIndex].setName(cp.getName());
					}
					preparerList.addAll(Arrays.asList(cbCon.preparers));
					index++;
				}
			}
		}
		if (buf.length() > 1)
		{
			buf.append(')');
			this.preparedCondition = buf.toString();
		}
		else
		{
			this.preparedCondition = "";
		}
		if (log.isDebugEnabled())
		{
			log.debug("Condition:" + buf.toString());
		}
		int pSize = preparerList.size();
		if (pSize > 0)
		{
			PreparerManager tmpPM = new PreparerManager(pSize);
			Iterator itr = preparerList.iterator();
			for (int i = 0; i < pSize; i++)
			{
				ValuePreparer preparer = (ValuePreparer) itr.next();
				preparer.setRelativeIndex(i + 1);
				tmpPM.setValuePreparer(preparer);
			}
			this.generatedPM = tmpPM;
		}
		else
		{
			this.generatedPM = null;
		}
	}

	public PreparerManager getPreparerManager()
	{
		return this.generatedPM;
	}

	public PreparerManager getSpecialPreparerManager(SearchAdapter search)
			throws ConfigurationException
	{
		if (search == this.resetConditionSearch)
		{
			return this.getPreparerManager();
		}
		if (this.specialMap != null)
		{
			Object[] temp = (Object[]) this.specialMap.get(search.getName());
			if (temp != null)
			{
				return (PreparerManager) temp[1];
			}
		}
		return (PreparerManager) this.createSpecialCondition(search)[1];
	}

	public String getConditionPart()
	{
		if (this.preparedCondition == null)
		{
			this.preparedCondition = "";
		}
		return this.preparedCondition;
	}

	public String getConditionPart(boolean needWrap)
	{
		String r = this.getConditionPart();
		return needWrap || r.length() == 0 ? r : r.substring(1, r.length() - 1);
	}

	public String getSpecialConditionPart(SearchAdapter search)
			throws ConfigurationException
	{
		if (search == this.resetConditionSearch)
		{
			return this.getConditionPart();
		}
		if (this.specialMap != null)
		{
			Object[] temp = (Object[]) this.specialMap.get(search.getName());
			if (temp != null)
			{
				return (String) temp[0];
			}
		}
		return (String) this.createSpecialCondition(search)[0];
	}

	public String getSpecialConditionPart(SearchAdapter search, boolean needWrap)
			throws ConfigurationException
	{
		if (search == this.resetConditionSearch)
		{
			return this.getConditionPart(needWrap);
		}
		String r = this.getSpecialConditionPart(search);
		return needWrap || r.length() == 0 ? r : r.substring(1, r.length() - 1);
	}


	private Map prepareCondition(SearchAdapter search)
			throws ConfigurationException
	{
		List cons = this.getConditions();
		if (cons.size() == 0)
		{
			return null;
		}
		Map result = new HashMap();
		Iterator itr = cons.iterator();
		while (itr.hasNext())
		{
			Condition con = (Condition) itr.next();
			ConditionProperty cp = search.getConditionProperty(con.name);
			if (cp != null && !cp.isIgnore())
			{
				List group = (List) result.get(con.group);
				if (group == null)
				{
					group = new LinkedList();
					result.put(con.group, group);
				}
				group.add(con);
				group.add(cp);
			}
		}
		return result;
	}

	/**
	 * 生成条件子集，返回值为2个，第一个为ConditionPart，第二个为PreparerManager.
	 */
	private Object[] createSpecialCondition(SearchAdapter search)
			throws ConfigurationException
	{
		Map consMap = this.prepareCondition(search);
		if (consMap == null || consMap.size() == 0)
		{
			return new Object[]{"", null};
		}
		StringAppender buf = StringTool.createStringAppender(512);
		List preparerList = new LinkedList();
		Iterator gitr = consMap.values().iterator();
		String value;
		while (gitr.hasNext())
		{
			List conditions = (List) gitr.next();
			if (conditions.size() > 0)
			{
				Iterator citr = conditions.iterator();
				int index = 0;
				while (citr.hasNext())
				{
					Condition condition = (Condition) citr.next();
					ConditionProperty cp = (ConditionProperty) citr.next();
					ConditionBuilder cb = cp.isUseDefaultConditionBuilder() ?
							cp.getDefaultConditionBuilder() : condition.builder;
					value = condition.value;
					ConditionBuilder.Condition cbCon = null;
					try
					{
						cbCon = cb.buildeCondition(cp.getColumnName(), value, cp);
					}
					catch (Exception ex)
					{
						log.error("Error wen builde condition: ConditionProperty[" + cp.getName()
								+ "], search[" + search.getName() + "]", ex);
					}
					if (log.isDebugEnabled())
					{
						log.debug("OneCondition:" + cbCon.toString());
					}
					if (cbCon != null)
					{
						if (index != 0)
						{
							buf.append(" AND ");
						}
						else
						{
							if (buf.length() > 0)
							{
								buf.append(" OR (");
							}
							else
							{
								buf.append("( (");
							}
						}
						buf.append(cbCon.sqlPart);
						for (int pIndex = 0; pIndex < cbCon.preparers.length; pIndex++)
						{
							cbCon.preparers[pIndex].setName(cp.getName());
						}
						preparerList.addAll(Arrays.asList(cbCon.preparers));
						index++;
					}
				}
				if (buf.length() > 0)
				{
					buf.append(')');
				}
			}
		}
		if (buf.length() > 0)
		{
			buf.append(" )");
		}
		String pCon = buf.toString();
		if (log.isDebugEnabled())
		{
			log.debug("Condition:" + buf.toString());
		}
		int pSize = preparerList.size();
		PreparerManager gPM = null;
		if (pSize > 0)
		{
			gPM = new PreparerManager(pSize);
			Iterator itr = preparerList.iterator();
			for (int i = 0; i < pSize; i++)
			{
				ValuePreparer preparer = (ValuePreparer) itr.next();
				preparer.setRelativeIndex(i + 1);
				gPM.setValuePreparer(preparer);
			}
		}
		Object[] result = new Object[]{pCon, gPM};
		if (this.specialMap == null)
		{
			this.specialMap = new HashMap();
		}
		this.specialMap.put(search.getName(), result);
		return result;
	}

	public Attributes getAttributes()
	{
		return this.attributes;
	}

	public void setAttributes(Attributes attributes)
	{
		this.attributes = attributes == null ? DEFAULT_PROPERTIES : attributes;
	}

	public Condition getCondition(String name)
	{
		if (this.conditionMap == null)
		{
			return null;
		}
		List list = (List) this.conditionMap.get(name);
		if (list == null)
		{
			return null;
		}
		return (Condition) list.get(0);
	}

	public List getConditions(String name)
	{
		if (this.conditionMap == null)
		{
			return null;
		}
		List list = (List) this.conditionMap.get(name);
		if (list == null)
		{
			return null;
		}
		return Collections.unmodifiableList(list);
	}

	public List getConditions()
	{
		if (this.conditionList == null)
		{
			return Collections.EMPTY_LIST;
		}
		return Collections.unmodifiableList(this.conditionList);
	}

	private void clearCondition()
	{
		this.specialMap = null;
		if (this.conditionList != null)
		{
			this.conditionList.clear();
			this.conditionMap.clear();
		}
	}

	private void addCondition(Condition condition)
	{
		if (this.conditionList == null)
		{
			this.conditionList = new LinkedList();
			this.conditionMap = new HashMap();
		}
		this.conditionList.add(condition);
		ArrayList conditions = (ArrayList) this.conditionMap.get(condition.name);
		if (conditions == null)
		{
			conditions = new ArrayList(2);
			this.conditionMap.put(condition.name, conditions);
		}
		conditions.add(condition);
	}

	public Object create()
			throws ConfigurationException
	{
		return this.createSearchManager();
	}

	public SearchManager createSearchManager()
			throws ConfigurationException
	{
		return new SearchManagerImpl();
	}

	public void print(DataPrinter p, Writer out, Object bean)
			throws IOException, ConfigurationException
	{
		Iterator itr = this.getConditions().iterator();
		boolean firstValue = true;
		p.printObjectBegin(out);
		while (itr.hasNext())
		{
			Condition con = (Condition) itr.next();
			if (con.value != null)
			{
				p.printPair(out, con.name, con.value, firstValue);
				firstValue = false;
			}
		}
		p.printObjectEnd(out);
	}

}