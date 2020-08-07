
CREATE TABLE IF NOT EXISTS employee (
 eid int comment '员工id' ,
 name String comment '员工姓名',
salary String comment '员工薪水',
destination String comment '员工职级')
COMMENT '员工表'
 ROW FORMAT DELIMITED
   FIELDS TERMINATED BY '\001'
STORED AS SEQUENCEFILE;

-- https://cwiki.apache.org/confluence/display/Hive/LanguageManual+DDL#LanguageManualDDL-CreateTableCreate/Drop/TruncateTable
CREATE TABLE page_view(viewTime INT, userid BIGINT,
     page_url STRING, referrer_url STRING,
     ip STRING COMMENT 'IP Address of the User')
 COMMENT 'This is the page view table'
 PARTITIONED BY(dt STRING, country STRING)
 ROW FORMAT DELIMITED
   FIELDS TERMINATED BY '\001'
STORED AS SEQUENCEFILE;



CREATE TABLE `test_atlas_desc`.`salary`
(
  `id1` string COMMENT "字段一" ,
  `id2` string COMMENT "字段二" ) ROW FORMAT   DELIMITED
    FIELDS TERMINATED BY ','
    COLLECTION ITEMS TERMINATED BY '\002'
    MAP KEYS TERMINATED BY '\003'
  STORED AS TextFile


CREATE TABLE `qy_szrdb_test_kettle` (
  `SZRDID` bigint ,
  `QYID` bigint    ,
  `NSQXDM` string     COMMENT '纳税期限代码',
  `K_WZBB`string    ,
  `K_SZID`string    ,
  `K_GDSBZ` string     COMMENT '国地税标志：1-国税；2-地税；0-非税务',
  `K_SMID` string    ,
  `K_YSSMID` string    ,
  `K_XZQHID` string    ,
  `ZSFS_DM` string     COMMENT '征收方式代码',
  `SBQXDM` string     COMMENT '申报期限代码',
  `K_SBSZID` string     COMMENT '一共8位数，1-4位代表区域，不满4位后面补0，第5位代表国税、地税（国税：1，地税：0），后面3位为序列号。例如：江苏省国税企业所得税报表 32001001',
  `SL` double     COMMENT '税率',
  `ZSL` double     COMMENT '征收率',
  `HDZSL` double     COMMENT '核定征收率',
  `HDBL` double     COMMENT '核定比例',
  `HDJE` double     COMMENT '核定金额',
  `ZSFSMC` string     COMMENT '征收方式名称',
  `NSQXMC` string     COMMENT '纳税期限名称',
  `SBQXMC` string     COMMENT '申报期限名称',
  `SBFSMC` string      COMMENT '申报方式名称',
  `YXRQQ` string     COMMENT '有效日期起',
  `YXRQZ` string     COMMENT '有效日期止',
  `JDRQ` string     COMMENT '鉴定日期',
  `TBRQ` string     COMMENT '同步日期',
  `LRRYBM` bigint     COMMENT '录入人员编码',
  `LRSJ` string     COMMENT '录入时间',
  `DKDJ` string      COMMENT '代扣代缴',
  `DFJS` string      COMMENT '带附加税',
  `HDYSSDL` double     COMMENT '核定应税所得率-浙江国税',
  `SJSZID` bigint     COMMENT '上级税种id',
  `CREATE_TIME` string    COMMENT '创建时间',
  `K_SFCFL` string COMMENT '是否重分类 0-不重分类 1-全部重分类 2-部分重分类',
  `SFBB` string  COMMENT '是否必报：必报-1 选报-0',
  `LYLX` string  COMMENT '来源类型：0-采集 1-手动 2-原采集后手动修改',
  `UPstring_TIME` string      COMMENT '更新时间',
  `KJND` int     COMMENT '会计年度',
  `KJQJ` int     COMMENT '会计期间',
  `SBLX` string     COMMENT '申报类型',
  `UPstring_USER_ID` bigint     COMMENT '修改人',
  `CREATE_USER_ID` bigint     COMMENT '创建人',
  `kjzdId` bigint     COMMENT '财务备案制度',
  `isDelete` int     COMMENT '是否删除 1:删除 0:正常'
  )
comment '税种认定表——测试kettle'
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS parquet
;