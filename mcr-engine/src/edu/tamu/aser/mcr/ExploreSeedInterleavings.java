package edu.tamu.aser.mcr;
/*******************************************************************************
 * Copyright (c) 2013 University of Illinois
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;

import edu.tamu.aser.mcr.config.Configuration;
import edu.tamu.aser.mcr.constraints.ConstraintsBuildEngine;
import edu.tamu.aser.mcr.trace.AbstractNode;
import edu.tamu.aser.mcr.trace.IMemNode;
import edu.tamu.aser.mcr.trace.ReadNode;
import edu.tamu.aser.mcr.trace.Trace;
import edu.tamu.aser.mcr.trace.WriteNode;

//import edu.tamu.aser.rvinstrumentation;

/**
 * The MCRTest class implements maximal causal model based systematic
 * testing algorithm based on constraint solving. 
 * The events in the trace are loaded and processed window 
 * by window with a configurable window size. 
 * 
 * @author jeffhuang
 *
 */
public class ExploreSeedInterleavings {

	private static int schedule_id;
	private static Queue<List<String>> schedules = new ConcurrentLinkedQueue<List<String>>();
//	private static HashSet<IViolation> violations= new HashSet<IViolation>();
	
	public static String output = "#Read, #Constraints, SolvingTime(ms)\n";
    public static HashSet<Object> races = new HashSet<Object>();

	private static boolean isfulltrace =false;

	/**
	 * Trim the schedule to show the last 100 only entries
	 * 
	 * @param schedule
	 * @return
	 */
	private static Vector<String> trim(Vector<String> schedule)
	{
		if(schedule.size()>100)
		{
			Vector<String> s = new Vector<String>();
			s.add("...");
			for(int i=schedule.size()-100;i<schedule.size();i++)
				s.add(schedule.get(i));
			return s;
		}
		else
			return schedule;
	}
	
	/**
	 * The method for generating causally different schedules. 
	 * Each schedule enforces a read to be matched with a write that writes
	 * a different value.
	 * @param engine
	 * @param trace
	 * @param schedule_prefix
	 */
	private static void genereteCausallyDifferentSchedules( ConstraintsBuildEngine engine, Trace trace, Vector<String> schedule_prefix)
	{
		/*
		 * for each shared variable, find all the reads and writes to this variable
		 * group the writes based on the value written to this variable
		 * consider each read to check if it can see a different value
		 */
		Iterator<String> 
		addrIt = trace.getIndexedThreadReadWriteNodes().keySet().iterator();
		while(addrIt.hasNext())
		{
			
			//the dynamic memory location
			String addr = addrIt.next();	
			
			//get the initial value on this address
			final String initVal = trace.getInitialWriteValueMap().get(addr);
			
			//get all read nodes on the address
			Vector<ReadNode> readnodes = trace.getIndexedReadNodes().get(addr);
			
			//get all write nodes on the address
			Vector<WriteNode> writenodes = trace.getIndexedWriteNodes().get(addr);
			
			//skip if there is no write events to the address
			if(writenodes==null||writenodes.size()<1)
			continue;

			//check if local variable
			if(trace.isLocalAddress(addr))
				continue;
							
			HashMap<String,ArrayList<WriteNode>> valueMap = new HashMap<String,ArrayList<WriteNode>>();
			//group writes by their value
			for(int i=0;i<writenodes.size();i++)
			{
				WriteNode wnode = writenodes.get(i);//write
				String v = wnode.getValue();
				ArrayList<WriteNode> list = valueMap.get(v);
				if(list==null){
					list = new ArrayList<WriteNode>();
					valueMap.put(v, list);
				}
				list.add(wnode);
			}
				
			//check read-write
			if(readnodes!=null){
				for(int i=0;i<readnodes.size();i++)
				{
					ReadNode rnode = readnodes.get(i);
					//if isfulltrace, only consider the read nodes that happen after the prefix
					if(isfulltrace && rnode.getGID()<=schedule_prefix.size())
						continue;
			
					String rValue = rnode.getValue();
					//1. first check if the rnode can read from the initial value which is different from rValue
					boolean success = false;
					if(initVal==null && !rValue.equals("0") 
							||initVal!=null && !initVal.equals(rValue))
					{
						success = checkInitial(engine, trace, schedule_prefix, writenodes,
								rnode, initVal);			
					}
					
					//2. then check if it can read from a particular write
					for(Iterator<String> valueIter=valueMap.keySet().iterator();valueIter.hasNext();)
					{
						final String wValue = valueIter.next();
						if( !wValue.equals(rValue))  
						{		
							//if it already reads from the initial value, then skip it
							if (wValue.equals(initVal) && success) {
								continue;
							}
							checkReadWrite(engine, trace, schedule_prefix,valueMap, rnode, wValue);
						}
					}
				} //end for check read write
			}
		}  //end while
		
		//TODO: fix the initial value read problem

	}
	
	
	private static boolean checkInitial(ConstraintsBuildEngine engine, Trace trace,
			Vector<String> schedule_prefix, Vector<WriteNode> writenodes,
			ReadNode rnode, String initVal) {
		//construct constraints and generate schedule
		HashSet<AbstractNode> depNodes = engine.getDependentNodes(trace,rnode);
		
		HashSet<AbstractNode> readDepNodes = new HashSet<AbstractNode>();
		
		if(isfulltrace && schedule_prefix.size()>0)
			depNodes.addAll(trace.getFullTrace().subList(0, schedule_prefix.size()));
		
		
		depNodes.add(rnode);
		readDepNodes.add(rnode);

		WriteNode wnode = null;
		StringBuilder sb = engine.constructFeasibilityConstraints(trace,depNodes,readDepNodes, rnode, wnode);
		StringBuilder sb2 = engine.constructReadInitWriteConstraints(rnode,depNodes, writenodes);

		sb.append(sb2);		
		//@alan
		//adding rnode.getGid() as a parameter
		Vector<String> schedule = engine.generateSchedule(sb,rnode.getGID(),rnode.getGID(),isfulltrace?schedule_prefix.size():0);
		
		output = output + Long.toString(Configuration.numReads) + " " +
				Long.toString(Configuration.rwConstraints) + " " +
				Long.toString(Configuration.solveTime) + "\n";
			
		if(schedule!=null){
			generateSchedule(schedule,trace,schedule_prefix,rnode.getID(),rnode.getValue(),initVal,-1);
			return true;
		}
		return false;
	}

	/**
	 * check if a read can read from a particular write
	 * @param engine
	 * @param trace
	 * @param schedule_prefix: the prefix that guides this execution
	 * @param valueMap
	 * @param rnode: the target read
	 * @param wValue: the value the read expects to see
	 */
	private static void checkReadWrite(
			ConstraintsBuildEngine engine, 
			Trace trace,
			Vector<String> schedule_prefix,
			HashMap<String, ArrayList<WriteNode>> valueMap, 
			ReadNode rnode,
			String wValue) 
	{	
		Vector<AbstractNode> otherWriteNodes = new Vector<AbstractNode>();
		Iterator<Entry<String, ArrayList<WriteNode>>> entryIt = valueMap.entrySet().iterator();
		while(entryIt.hasNext())
		{
			Entry<String, ArrayList<WriteNode>> entry= entryIt.next();
			if(!entry.getKey().equals(wValue))
				otherWriteNodes.addAll(entry.getValue());
		}

		ArrayList<WriteNode> wnodes = valueMap.get(wValue);
		for(int j=0;j<wnodes.size();j++)
		{
			WriteNode wnode = wnodes.get(j);
			if(rnode.getTid() != wnode.getTid())
			{
		
				//check whether possible for read to match with write
				//can reach means a happens before relation
				//the first if-condition is a little strange
				if(rnode.getGID() > wnode.getGID() || !engine.canReach(rnode, wnode))
				{
					
					//for all the events that happen before the target read and chosen write
					HashSet<AbstractNode> depNodes = new HashSet<AbstractNode>();
					
					//only for all the events that happen before the target read
					HashSet<AbstractNode> readDepNodes = new HashSet<AbstractNode>();
					
					if(isfulltrace&&schedule_prefix.size()>0)
						depNodes.addAll(trace.getFullTrace().subList(0, schedule_prefix.size()));
					
					//1. first find all the dependent nodes
					depNodes.add(rnode);
					depNodes.add(wnode);
					
					readDepNodes.add(rnode);		
					
					/*
					 * After using static analysis, some reads can be removed
					 * but they cannot be removed, otherwise the order calculated will be wrong
					 * it just needs to ignore the feasibility constraints of these reads
					 * @author Alan
					 */
					HashSet<AbstractNode> nodes1 = engine.getDependentNodes(trace,rnode);
					HashSet<AbstractNode> nodes2 = engine.getDependentNodes(trace,wnode);
										
					depNodes.addAll(nodes1); 
					depNodes.addAll(nodes2);
					
					readDepNodes.addAll(nodes1);
					
					//construct feasibility constraints
					StringBuilder sb = 
							engine.constructFeasibilityConstraints(trace,depNodes,readDepNodes, rnode, wnode);
					
					//construct read write constraints
					StringBuilder sb3 = 
							engine.constructReadWriteConstraints(engine,trace,depNodes, rnode, wnode, otherWriteNodes);
					
					sb.append(sb3);

					Vector<String> schedule = 
							engine.generateSchedule(sb,rnode.getGID(), wnode.getGID(),isfulltrace?schedule_prefix.size():0);
					
					//each time compute a causal schedule, record the information of #read, #constraints, time
					output = output + Long.toString(Configuration.numReads) + " " +
							Long.toString(Configuration.rwConstraints) + " " +
							Long.toString(Configuration.solveTime) + "\n";
	
					
					if(schedule!=null)
					{
						//rnode.getID or GID??
						generateSchedule(schedule,trace,schedule_prefix,rnode.getID(),rnode.getValue(),wValue,wnode.getID());
						break;
					}
				}
			}
		}
	}

	/**
	 * Generate the causal schedule
	 * @param schedule: constructed from the solution
	 * @param trace:    the given trace
	 * @param schedule_prefix  
	 * @param rGid: the global ID of the read event
	 * @param rValue : old value
	 * @param wValue : new value
	 * @param wID : the global ID of the write event
	 */
	private static void generateSchedule(Vector<String> schedule, Trace trace,
			Vector<String> schedule_prefix, int rGid, String rValue, String wValue, int wID)
	{
		{	
			Vector<String> schedule_a = new Vector<String>();
			
			//get the first start event
			//note that in the first execution, there might be some events before the start event
			//but for the next execution, these events will not be executed
			
			//but for RV example, these events are executed for the next execution
			//for the implementation, just make all the assignments under main
			
			//@Alan
			
			int start_index = 0;
//			if (schedule_prefix.size() == 0) {
//				start_index = 0;
//				while(start_index < schedule.size()){
//					String xi = schedule.get(start_index);
//					long gid = Long.valueOf(xi.substring(1));
//					AbstractNode start_node = trace.getFullTrace().get((int) (gid-1));
//					if (start_node instanceof StartNode) {
//						break;
//					}
//					start_index++;
//				}
//				
//			}
			
			for (int i=start_index; i<schedule.size(); i++)
			{
				String xi = schedule.get(i);
				long gid = Long.valueOf(xi.substring(1));
				long tid = trace.getNodeGIDTIdMap().get(gid);
				String name = trace.getThreadIdNameMap().get(tid);	
				
				//add addr info to the schedule 
				//the addr information is needed when replay yhe TSO/PSO schedule
				if (Configuration.mode=="TSO" || Configuration.mode=="PSO") 
				{
					String addr="";
					AbstractNode node = trace.getFullTrace().get((int) (gid-1));
					if(node instanceof ReadNode || node instanceof WriteNode){
						addr = ((IMemNode) node).getAddr();			
						if(addr.split("\\.") [0] != addr )
							addr = addr.split("\\.")[1];					
					}			
					if(addr==""){
						addr=""+node.getType();
					}
					name = name + "_" + addr;
				}					
				schedule_a.add(name);
			}
						
			if(!isfulltrace) {
			    //add the schedule prefix to the head of the new schedule to make it a complete schedule
				schedule_a.addAll(0, schedule_prefix);
			} else {
				//System.out.println(" USING FULL TRACE************");
			}
			schedules.add(schedule_a);
			
			if(Configuration.DEBUG)
			{
				//report the schedules
				String msg_header = "************* causal schedule "+(++schedule_id)+" **************\n";
				String msg_rwmeta = "Read-Write: "+trace.getStmtSigIdMap().get(rGid)+" -- "+(wID<0?"init":trace.getStmtSigIdMap().get(wID))+"\n";
				String msg_rwvalue = "Values: ("+rValue+"-"+wValue+")     ";
				String msg_schedule = "Schedule: "+trim(schedule_a)+"\n";
				String msg_footer = "**********************************************\n";
				
				report(msg_header+msg_rwmeta+msg_rwvalue+msg_schedule+msg_footer,MSGTYPE.STATISTICS);
			}
		}	
	}
	
	private static PrintWriter out;
	private static ConstraintsBuildEngine iEngine;
	/**
	 * Initialize the file printer. All race detection statistics are stored
	 * into the file result."window_size".
	 * @param appname
	 */
	private static void initPrinter(String appname)
	{
		if(out==null)
		try{
//		String fname = "dataraces."+appname;
//		out = new PrintWriter(new FileWriter(fname,true));
		
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}
	
	private static void report(String msg, MSGTYPE type)
	{
		switch(type)
		{
		case REAL:
		case STATISTICS:
			System.err.println(msg);
//			out.println(msg);
//			out.flush();
			break;
		default: break;
		}
	}
	public enum MSGTYPE
	{
		REAL,POTENTIAL,STATISTICS
	}
	private static ConstraintsBuildEngine getEngine(String name)
	{
		if(iEngine==null){
	        Configuration config = new Configuration();
	        config.tableName = name;
	        config.constraint_outdir = "tmp" + System.getProperty("file.separator") + "smt";

	        iEngine = new ConstraintsBuildEngine(config);//EngineSMTLIB1
		}
		
			return iEngine;
	}
	public static void setQueue(Queue<List<String>> queue) {
		
		schedules = queue;
	}
	
	/**
	 * start exploring the trace
	 * @param trace
	 * @param schedule_prefix
	 */
	public static void execute(Trace trace, Vector<String> schedule_prefix) {
		
		Configuration.numReads = 0;
		Configuration.rwConstraints = 0;
		Configuration.solveTime = 0;
			
		//OPT: if #sv==0 or #shared rw ==0 continue	
		if(trace.hasSharedVariable())
		{
			initPrinter(trace.getApplicationName());
			
			//engine is used for constructing constraints model
			ConstraintsBuildEngine engine = getEngine(trace.getApplicationName());
			
			//pre-process the trace
			//build the initial happen before relation for some optimization
			engine.preprocess(trace);
		
			//generate causal prefixes
			genereteCausallyDifferentSchedules(engine,trace,schedule_prefix);
			
		}		
	}
}
