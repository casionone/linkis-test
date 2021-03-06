/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.linkis.configuration.service

import java.lang.Long
import java.util

import org.apache.linkis.common.utils.Logging
import org.apache.linkis.configuration.conf.Configuration
import org.apache.linkis.configuration.dao.{ConfigMapper, LabelMapper}
import org.apache.linkis.configuration.entity.{ConfigKey, _}
import org.apache.linkis.configuration.exception.ConfigurationException
import org.apache.linkis.configuration.util.{LabelEntityParser, LabelParameterParser}
import org.apache.linkis.configuration.validate.ValidatorManager
import org.apache.linkis.governance.common.protocol.conf.ResponseQueryConfig
import org.apache.linkis.manager.common.protocol.conf.RemoveCacheConfRequest
import org.apache.linkis.manager.label.builder.CombinedLabelBuilder
import org.apache.linkis.manager.label.builder.factory.LabelBuilderFactoryContext
import org.apache.linkis.manager.label.constant.{LabelConstant, LabelKeyConstant}
import org.apache.linkis.manager.label.entity.engine.{EngineTypeLabel, UserCreatorLabel}
import org.apache.linkis.manager.label.entity.{CombinedLabel, CombinedLabelImpl, Label, SerializableLabel}
import org.apache.linkis.manager.label.utils.{EngineTypeLabelCreator, LabelUtils}
import org.apache.linkis.rpc.Sender
import org.apache.commons.lang.StringUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.CollectionUtils

import scala.collection.JavaConverters._

@Service
class ConfigurationService extends Logging {

  @Autowired private var configMapper: ConfigMapper = _

  @Autowired private var labelMapper: LabelMapper = _

  @Autowired private var validatorManager: ValidatorManager = _

  private  val combinedLabelBuilder: CombinedLabelBuilder = new CombinedLabelBuilder


  @Transactional
  def addKeyForEngine(engineType: String, version: String, key: ConfigKey): Unit ={
    val labelList = LabelEntityParser.generateUserCreatorEngineTypeLabelList("*","*",engineType,version)
    val combinedLabel = combinedLabelBuilder.build("", labelList).asInstanceOf[CombinedLabel]
    var label = labelMapper.getLabelByKeyValue(combinedLabel.getLabelKey, combinedLabel.getStringValue)
    var configs: util.List[ConfigKeyValue] = new util.ArrayList[ConfigKeyValue]()
    if(label != null && label.getId > 0){
      configs = configMapper.getConfigKeyValueByLabelId(label.getId)
    }else{
      val parsedLabel = LabelEntityParser.parseToConfigLabel(combinedLabel)
      labelMapper.insertLabel(parsedLabel)
      info(s"??????label?????????${parsedLabel.getStringValue}")
      label = parsedLabel
    }
    val existsKey = configs.asScala.map(_.getKey).contains(key.getKey)
    if(!existsKey){
      configMapper.insertKey(key)
      info(s"??????Key?????????${key.getKey}")
    }else{
      configs.asScala.foreach(conf => if(conf.getKey.equals(key.getKey)) key.setId(conf.getId))
    }
    val existsConfigValue = configMapper.getConfigKeyValueByLabelId(label.getId)
    if(existsConfigValue == null){
      val configValue = new ConfigValue()
      configValue.setConfigKeyId(key.getId)
      configValue.setConfigValue("")
      configValue.setConfigLabelId(label.getId)
      configMapper.insertValue(configValue)
      info(s"key???label???????????????key:${key.getKey},label:${label.getStringValue}")
    }
  }

  def insertCreator(creator:String): Unit ={
    val creatorID: Long = configMapper.selectAppIDByAppName(creator)
    if(creatorID == null) configMapper.insertCreator(creator) else warn(s"creator${creator} exists")
  }

  @Transactional
  def copyKeyFromIDE(key:ConfigKey,creator:String, appName:String) ={
    /*val creatorID: Long = configMapper.selectAppIDByAppName(creator)
    key.setApplicationID(creatorID)
    val treeID = configMapper.selectTreeIDByKeyID(key.getId)
    key.setId(null)
    configMapper.insertKey(key)
    configMapper.insertKeyTree(key.getId,treeID)*/
  }


  def checkAndCreateUserLabel(settings: util.List[ConfigKeyValue], username: String, creator: String): Integer ={
    var labelId: Integer = null
    if(!settings.isEmpty){
      val setting = settings.get(0)
      val configLabel = labelMapper.getLabelById(setting.getConfigLabelId)
      val combinedLabel = combinedLabelBuilder.buildFromStringValue(configLabel.getLabelKey,configLabel.getStringValue).asInstanceOf[CombinedLabel]
      combinedLabel.getValue.asScala.foreach(label => {
        if (label.isInstanceOf[UserCreatorLabel]) {
          if (label.asInstanceOf[UserCreatorLabel].getUser.equals(LabelUtils.COMMON_VALUE)) {
            label.asInstanceOf[UserCreatorLabel].setUser(username)
            label.asInstanceOf[UserCreatorLabel].setCreator(creator)
            val parsedLabel = LabelEntityParser.parseToConfigLabel(combinedLabel)
            val userLabel = labelMapper.getLabelByKeyValue(parsedLabel.getLabelKey, parsedLabel.getStringValue)
            if (userLabel == null) {
              labelMapper.insertLabel(parsedLabel)
              labelId = parsedLabel.getId
            }else{
              labelId = userLabel.getId
            }
          }else{
            labelId = configLabel.getId
          }
        }
      })
    }
    if(labelId == null){
      throw new ConfigurationException("create user label false, cannot save user configuration!(????????????label???????????????????????????????????????)")
    }
    labelId
  }

  def updateUserValue(createList: util.List[ConfigValue], updateList: util.List[ConfigValue]): Unit = {
    if(!CollectionUtils.isEmpty(createList)){
      configMapper.insertValueList(createList)
    }
    if(!CollectionUtils.isEmpty(updateList)){
      configMapper.updateUserValueList(updateList)
    }
  }

  def clearAMCacheConf(username: String, creator: String, engine: String, version: String): Unit = {
    val sender = Sender.getSender(Configuration.MANAGER_SPRING_NAME.getValue)
    if(StringUtils.isNotEmpty(username)){
      val userCreatorLabel = LabelBuilderFactoryContext.getLabelBuilderFactory.createLabel(classOf[UserCreatorLabel])
      userCreatorLabel.setUser(username)
      userCreatorLabel.setCreator(creator)
      val request = new RemoveCacheConfRequest
      request.setUserCreatorLabel(userCreatorLabel)
      if(StringUtils.isNotEmpty(engine) && StringUtils.isNotEmpty(version)){
        val engineTypeLabel = EngineTypeLabelCreator.createEngineTypeLabel(engine)
        engineTypeLabel.setVersion(version)
        request.setEngineTypeLabel(engineTypeLabel)
      }
      sender.ask(request)
    }
  }

  def updateUserValue(setting: ConfigKeyValue, userLabelId: Integer,
                      createList: util.List[ConfigValue], updateList: util.List[ConfigValue]) = {
    paramCheck(setting)
    if(setting.getIsUserDefined){
      val configValue = new ConfigValue
      if(StringUtils.isEmpty(setting.getConfigValue)){
        configValue.setConfigValue("")
      }else{
        configValue.setConfigValue(setting.getConfigValue)
      }
      configValue.setId(setting.getValueId)
      updateList.add(configValue)
    }else{
      if(!StringUtils.isEmpty(setting.getConfigValue)){
        val configValue = new ConfigValue
        configValue.setConfigKeyId(setting.getId)
        configValue.setConfigLabelId(userLabelId)
        configValue.setConfigValue(setting.getConfigValue)
        createList.add(configValue)
      }
    }
  }

  def paramCheck(setting: ConfigKeyValue) = {
    if (!StringUtils.isEmpty(setting.getConfigValue)) {
      var key: ConfigKey = null
      if(setting.getId != null){
        key = configMapper.selectKeyByKeyID(setting.getId)
      }else{
        key = configMapper.seleteKeyByKeyName(setting.getKey)
      }
      if(key == null){
        throw new ConfigurationException("config key is null, please check again!(????????????????????????????????????key???)")
      }
      info(s"parameter ${key.getKey} value ${setting.getConfigValue} is not empty, enter checksum...(??????${key.getKey} ???${setting.getConfigValue}????????????????????????...)")
      if (!validatorManager.getOrCreateValidator(key.getValidateType).validate(setting.getConfigValue, key.getValidateRange)) {
        throw new ConfigurationException(s"Parameter verification failed(??????????????????):${key.getKey}--${key.getValidateType}--${key.getValidateRange}--${setting.getConfigValue}")
      }
    }
  }

  def paramCheckByKeyValue(key: String, value: String) = {
    val setting = new ConfigKeyValue
    setting.setKey(key)
    setting.setConfigValue(value)
    paramCheck(setting)
  }

  def listAllEngineType(): Array[String] = {
    val engineTypeString = Configuration.ENGINE_TYPE.getValue
    val engineTypeList = engineTypeString.split(",")
    engineTypeList
  }

  def generateCombinedLabel(engineType: String = "*", version: String, userName: String = "*", creator: String = "*"): CombinedLabel = {
    val labelList = LabelEntityParser.generateUserCreatorEngineTypeLabelList(userName, creator, engineType, version)
    val combinedLabel = combinedLabelBuilder.build("",labelList)
    combinedLabel.asInstanceOf[CombinedLabelImpl]
  }

  def buildTreeResult(configs: util.List[ConfigKeyValue],defaultConfigs:util.List[ConfigKeyValue] = new util.ArrayList[ConfigKeyValue]()): util.ArrayList[ConfigTree] = {
    var resultConfigs: util.List[ConfigKeyValue] = new util.ArrayList[ConfigKeyValue]()
    if(!defaultConfigs.isEmpty){
      defaultConfigs.asScala.foreach(defaultConfig => {
        defaultConfig.setIsUserDefined(false)
        configs.asScala.foreach(config => {
          if(config.getKey != null && config.getKey.equals(defaultConfig.getKey)){
            if(config.getConfigValue != null){
              defaultConfig.setConfigValue(config.getConfigValue)
              defaultConfig.setConfigLabelId(config.getConfigLabelId)
              defaultConfig.setValueId(config.getValueId)
              defaultConfig.setIsUserDefined(true)
            }
          }
        })
      })
      resultConfigs = defaultConfigs
    }
    val treeNames = resultConfigs.asScala.map(config => config.getTreeName).distinct
    val resultConfigsTree = new util.ArrayList[ConfigTree](treeNames.length)
    resultConfigsTree.addAll(treeNames.map(treeName => {
      val configTree = new ConfigTree()
      configTree.setName(treeName)
      configTree.getSettings.addAll(resultConfigs.asScala.filter(config => config.getTreeName.equals(treeName)).toList.asJava)
      configTree
    }).toList.asJava)
    resultConfigsTree
  }

//  def getFullTree(engineType: String = "*", version: String, userName: String = "*", creator: String = "*", useDefaultConfig: Boolean = true): util.ArrayList[ConfigTree] = {
//    val combinedLabel = generateCombinedLabel(engineType, version,userName,creator)
//    info(s"start to get config by label???${combinedLabel.getStringValue}??????????????????????????????????????????${combinedLabel.getStringValue}???")
//    val label = labelMapper.getLabelByKeyValue(combinedLabel.getLabelKey,combinedLabel.getStringValue)
//    var configs: util.List[ConfigKeyValue] = new util.ArrayList[ConfigKeyValue]()
//    if(label != null && label.getId > 0){
//      configs = configMapper.getConfigKeyValueByLabelId(label.getId)
//    }
//    var defaultConfigs: util.List[ConfigKeyValue] = new util.ArrayList[ConfigKeyValue]()
//    if(useDefaultConfig) {
//      var defaultCombinedLabel: CombinedLabel = null
//      defaultCombinedLabel = generateCombinedLabel(engineType, version)
//      val defaultLabel = labelMapper.getLabelByKeyValue(defaultCombinedLabel.getLabelKey, defaultCombinedLabel.getStringValue)
//      if(defaultLabel != null){
//        defaultConfigs = configMapper.getConfigKeyValueByLabelId(defaultLabel.getId)
//      }
//      if(CollectionUtils.isEmpty(defaultConfigs)){
//        throw new ConfigurationException(s"The default configuration is empty. Please check the default configuration information in the database table(??????????????????,????????????????????????????????????${defaultCombinedLabel.getStringValue}?????????????????????????????????)")
//      }
//    }
//    //persisteUservalue(configs,defaultConfigs,combinedLabel,label)
//    info("finished to get config by label, start to build config tree(????????????????????????,?????????????????????)")
//    buildTreeResult(configs,defaultConfigs)
//  }

  def labelCheck(labelList: java.util.List[Label[_]]):Boolean = {
    if(!CollectionUtils.isEmpty(labelList)){
      labelList.asScala.foreach(label => label match {
        case a:UserCreatorLabel => Unit
        case a:EngineTypeLabel => Unit
        case _ => throw new ConfigurationException(s"this type of label is not supported:${label.getClass}(??????????????????????????????label${label.getClass})")
      })
      true
    }else{
      throw new ConfigurationException("The label parameter is empty(label???????????????????????????label??????????????????)")
    }
  }
  def getFullTreeByLabelList(labelList: java.util.List[Label[_]], useDefaultConfig: Boolean = true): util.ArrayList[ConfigTree] = {
    labelCheck(labelList)
    val combinedLabel = combinedLabelBuilder.build("",labelList).asInstanceOf[CombinedLabelImpl]
    info("start to get config by label??????????????????????????????????????????")
    val label = labelMapper.getLabelByKeyValue(combinedLabel.getLabelKey,combinedLabel.getStringValue)
    var configs: util.List[ConfigKeyValue] = new util.ArrayList[ConfigKeyValue]()
    if(label != null && label.getId > 0){
      configs = configMapper.getConfigKeyValueByLabelId(label.getId)
    }
    var defaultConfigs: util.List[ConfigKeyValue] = new util.ArrayList[ConfigKeyValue]()
    if(useDefaultConfig) {
      //todo ????????????????????????-->creator?????????????????????-->??????????????????,????????????creator???????????????
      //?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
      //????????????????????????????????????????????????
      //1.????????????????????????????????????????????????label???creator?????????????????????
      //2.???????????????????????????????????????createSecondCategory????????????linkedEngineTypeLabel???label??????????????????creator??????????????????creator
      //3.???????????????????????????????????????linkis.dml?????????--??????label?????????????????????label.label_value = @SPARK_ALL??????????????????@SPARK_IDE??????????????????
      //notice:??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
      val defaultLabelList = LabelParameterParser.changeUserToDefault(labelList)
      val defaultCombinedLabel = combinedLabelBuilder.build("", defaultLabelList).asInstanceOf[CombinedLabelImpl]
      val defaultLabel = labelMapper.getLabelByKeyValue(defaultCombinedLabel.getLabelKey, defaultCombinedLabel.getStringValue)
      if(defaultLabel != null){
        defaultConfigs = configMapper.getConfigKeyValueByLabelId(defaultLabel.getId)
      }
      if(CollectionUtils.isEmpty(defaultConfigs)){
        warn(s"The default configuration is empty. Please check the default configuration information in the database table(??????????????????,????????????????????????????????????${defaultCombinedLabel.getStringValue}?????????????????????????????????)")
      }
    }
    //persisteUservalue(configs,defaultConfigs,combinedLabel,label)
    info("finished to get config by label, start to build config tree(????????????????????????,?????????????????????)")
    buildTreeResult(configs,defaultConfigs)
  }


  @Transactional
   def persisteUservalue(configs: util.List[ConfigKeyValue], defaultConfigs:util.List[ConfigKeyValue], combinedLabel: CombinedLabel, existsLabel: ConfigLabel): Unit = {
    info(s"Start checking the integrity of user configuration data(??????????????????????????????????????????): label????????????${combinedLabel.getStringValue}")
    val userConfigList = configs.asScala
    val userConfigKeyIdList = userConfigList.map(config => config.getId)
    val defaultConfigsList = defaultConfigs.asScala
    val parsedLabel = LabelEntityParser.parseToConfigLabel(combinedLabel)
    if(existsLabel == null){
      info("start to create label for user(?????????????????????label)???" + "labelKey:" + parsedLabel.getLabelKey + " , " + "labelValue:" + parsedLabel.getStringValue)
      labelMapper.insertLabel(parsedLabel)
      info("Creation completed(???????????????)???" + parsedLabel)
    }
    defaultConfigsList.foreach(defaultConfig => {
      if(!userConfigKeyIdList.contains(defaultConfig.getId)){
        info(s"Initialize database configuration information for users(???????????????????????????????????????)???"+s"configKey: ${defaultConfig.getKey}")
        val configValue = new ConfigValue
        configValue.setConfigKeyId(defaultConfig.getId)
        if(existsLabel == null){
          configValue.setConfigLabelId(parsedLabel.getId)
        }else{
          configValue.setConfigLabelId(existsLabel.getId)
        }
        configValue.setConfigValue("")
        configMapper.insertValue(configValue)
        info(s"Initialization of user database configuration information completed(??????????????????????????????????????????)???configKey: ${defaultConfig.getKey}")
      }
    })
    info(s"User configuration data integrity check completed!(??????????????????????????????????????????): label????????????${combinedLabel.getStringValue}")
  }

    def queryConfigByLabel(labelList: java.util.List[Label[_]], isMerge:Boolean = true, filter:String = null): ResponseQueryConfig = {
      labelCheck(labelList)
      val allGolbalUserConfig = getFullTreeByLabelList(labelList)
      val defaultLabel = LabelParameterParser.changeUserToDefault(labelList)
      val allGolbalDefaultConfig = getFullTreeByLabelList(defaultLabel)
      val config = new ResponseQueryConfig
      config.setKeyAndValue(getMap(allGolbalDefaultConfig,allGolbalUserConfig,filter))
      config
  }


  def queryDefaultEngineConfig(engineTypeLabel: EngineTypeLabel): ResponseQueryConfig = {
    val labelList = LabelEntityParser.generateUserCreatorEngineTypeLabelList("*","*",engineTypeLabel.getEngineType,engineTypeLabel.getVersion)
    queryConfigByLabel(labelList)
  }

  def queryGlobalConfig(userName: String): ResponseQueryConfig = {
    val labelList = LabelEntityParser.generateUserCreatorEngineTypeLabelList(userName,"*","*","*")
    queryConfigByLabel(labelList)
  }

  def queryConfig(userCreatorLabel: UserCreatorLabel, engineTypeLabel: EngineTypeLabel, filter: String): ResponseQueryConfig ={
    val labelList = new util.ArrayList[Label[_]]
    labelList.add(userCreatorLabel)
    labelList.add(engineTypeLabel)
    queryConfigByLabel(labelList, true,filter)
  }

  def queryConfigWithGlobal(userCreatorLabel: UserCreatorLabel, engineTypeLabel: EngineTypeLabel, filter: String): ResponseQueryConfig ={
    val globalConfig = queryGlobalConfig(userCreatorLabel.getUser)
    val engineConfig = queryConfig(userCreatorLabel, engineTypeLabel, filter)
    globalConfig.getKeyAndValue.asScala.foreach(keyAndValue => {
      if(!engineConfig.getKeyAndValue.containsKey(keyAndValue._1)){
        engineConfig.getKeyAndValue.put(keyAndValue._1, keyAndValue._2)
      }
    })
    engineConfig
  }

  private def getMap(all: util.List[ConfigTree], user: util.List[ConfigTree], filter: String = null): util.Map[String, String] = {
    val map = new util.HashMap[String, String]()
    val allConfig = all.asScala.map(configTree => configTree.getSettings)
    val userConfig = user.asScala.map(configTree => configTree.getSettings)
    if(filter != null){
      allConfig.foreach(f => f.asScala.foreach(configKeyValue => if(configKeyValue.getKey.contains(filter)) map.put(configKeyValue.getKey,configKeyValue.getDefaultValue)))
      userConfig.foreach(f => f.asScala.foreach(configKeyValue => if(configKeyValue.getKey.contains(filter) && StringUtils.isNotEmpty(configKeyValue.getConfigValue)) map.put(configKeyValue.getKey,configKeyValue.getConfigValue)))
    }else{
      allConfig.foreach(f => f.asScala.foreach(configKeyValue => map.put(configKeyValue.getKey,configKeyValue.getDefaultValue)))
      userConfig.foreach(f => f.asScala.foreach(configKeyValue => if(StringUtils.isNotEmpty(configKeyValue.getConfigValue))map.put(configKeyValue.getKey,configKeyValue.getConfigValue)))
    }
    /*?????????????????????????????????????????????*/
    map
  }

}
