P="psql -h /tmp -p 5499 -U postgres -d t -qtA"
S=11111111-1111-1111-1111-111111111111; O=22222222-2222-2222-2222-222222222222
pass=0; fail=0
run(){ # name, uid(or none), expect(ok|err|value), sql, [expected value]
  local who="set role authenticated; select set_config('request.jwt.claim.sub','$2',false);"
  [ "$2" = anon ] && who="set role anon;"
  out=$($P -c "$who" -c "$4" 2>&1 | tail -1); rc=$?
  if [ "$3" = err ]; then echo "$out" | grep -qiE 'error|denied|violates' && r=PASS || r=FAIL
  elif [ "$3" = ok ]; then echo "$out" | grep -qiE 'error' && r=FAIL || r=PASS
  else [ "$out" = "$5" ] && r=PASS || r=FAIL; fi
  [ $r = PASS ] && pass=$((pass+1)) || fail=$((fail+1)); printf '%-4s %-55s -> %s\n' $r "$1" "$out"
}
run "Saurabh registers device"             $S ok  "insert into devices(device_id,person,model) values('w1','saurabh','SM-R870')"
run "Saurabh uploads 2 samples"            $S ok  "insert into samples(id,person,device_id,ts,type,value) values('w1:hr:1','saurabh','w1',now(),'hr',71),('w1:steps:1','saurabh','w1',now(),'steps',120)"
run "Re-upload same id = upsert, no dup"   $S ok  "insert into samples(id,person,device_id,ts,type,value) values('w1:hr:1','saurabh','w1',now(),'hr',72) on conflict(id) do update set value=excluded.value"
run "Still 2 samples, value updated to 72" $S val "select count(*)||'/'||max(value) filter (where type='hr') from samples where person='saurabh'" "2/72"
run "Saurabh writes as Priyanka -> blocked" $S err "insert into samples(id,person,ts,type,value) values('x','priyanka',now(),'hr',1)"
run "Saurabh moves row to Priyanka -> blocked" $S err "update samples set person='priyanka' where id='w1:hr:1'"
run "Saurabh deletes -> nothing deleted"   $S val "with d as (delete from samples returning 1) select count(*) from d" "ERROR:  permission denied for table samples"
run "Daily summary upsert"                 $S ok  "insert into daily_summary(person,date,steps) values('saurabh',current_date,5000) on conflict(person,date) do update set steps=excluded.steps"
run "Workout + sleep + label insert"       $S ok  "insert into workouts(id,person,start_ts,end_ts,exercise_type) values('wk1','saurabh',now()-interval '30 min',now(),'DANCING'); insert into sleep_sessions(id,person,start_ts,end_ts) values('sl1','saurabh',now()-interval '7 h',now()); insert into labels(id,person,date,kind,json) values('lb1','saurabh',current_date,'checkin','{\"rested\":4}')"
run "Workout ending before start -> blocked" $S err "insert into workouts(id,person,start_ts,end_ts) values('bad','saurabh',now(),now()-interval '1 h')"
run "Saurabh cannot write model_params"    $S err "insert into model_params(person,version,json) values('saurabh',1,'{}')"
run "Other user sees 0 of Saurabh's rows"  $O val "select count(*) from samples" "0"
run "Other user cannot read Saurabh people row" $O val "select count(*) from people where person='saurabh'" "0"
run "Anonymous cannot read samples"        anon err "select count(*) from samples"
run "Anonymous cannot insert"              anon err "insert into samples(id,person,ts,type) values('a','saurabh',now(),'hr')"
run "Unmapped login sees nothing"          33333333-3333-3333-3333-333333333333 val "select count(*) from samples" "0"
echo "RESULT: $pass passed, $fail failed"
