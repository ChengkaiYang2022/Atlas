-- 融资情况日报
truncate table test_atlas.ent_financing_information_daily;
insert into test_atlas.ent_financing_information_daily (round,round_count)
select round,count(1) from test_atlas.companyinfo_ent_financing_information where round != 'NULL' group by round

