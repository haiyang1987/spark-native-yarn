/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.tez;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.spark.tez.io.CacheRDD;
import org.apache.spark.tez.io.SparkDelegatingPartitioner;
import org.apache.spark.tez.io.TezRDD;
import org.apache.tez.client.TezClient;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.DataSinkDescriptor;
import org.apache.tez.dag.api.DataSourceDescriptor;
import org.apache.tez.dag.api.Edge;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.dag.api.UserPayload;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.dag.api.client.DAGClient;
import org.apache.tez.dag.api.client.DAGStatus;
import org.apache.tez.mapreduce.hadoop.MRHelpers;
import org.apache.tez.mapreduce.input.MRInput;
import org.apache.tez.mapreduce.output.MROutput;
import org.apache.tez.runtime.library.conf.OrderedPartitionedKVEdgeConfig;

import com.google.common.base.Preconditions;

/**
 * Builder which builds Tez DAG based on the collection of {@link VertexDescriptor}
 * added to it via {@link #addVertex(VertexDescriptor)}.
 * 
 * Once all Vertexes were added invoking {@link #build()} method will generate an executable
 * {@link DAGTask} (See {@link DAGTask#execute()} method)
 * 
 * The class is package private. Its main gateway is {@link Utils}.
 */
class DAGBuilder {
	
	private final Log logger = LogFactory.getLog(DAGBuilder.class);
	
	private final Configuration tezConfiguration;
	
	private final Map<Integer, VertexDescriptor> vertexes = new LinkedHashMap<Integer, VertexDescriptor>();
	
	private final Map<String, LocalResource> localResources;
	
	private final String applicationName;
	
	private DAG dag;
	
	private final LinkedHashSet<org.apache.spark.Partitioner> partitioners = new LinkedHashSet<org.apache.spark.Partitioner>();
	
	private Iterator<org.apache.spark.Partitioner> pIter;
	
	/**
	 * 
	 * @param tezClient
	 * @param localResources
	 * @param tezConfiguration
	 * @param outputPath
	 */
	public DAGBuilder(String applicationName, Map<String, LocalResource> localResources, Configuration tezConfiguration) {
		Preconditions.checkState(applicationName != null, "'applicationName' must not be null");
		Preconditions.checkState(localResources != null, "'localResources' must not be null");
		Preconditions.checkState(tezConfiguration != null, "'tezConfiguration' must not be null");
		
		this.tezConfiguration = tezConfiguration;
		this.applicationName = applicationName;
		this.dag = DAG.create(this.applicationName + "_" + System.currentTimeMillis());
		this.localResources = localResources;
	}
	
	private org.apache.spark.Partitioner getNextPartitioner() {
		if (this.pIter == null){
			this.pIter = this.partitioners.iterator();
		}
		if (pIter.hasNext()){
			return pIter.next();
		} else {
			return null;
		}
	}
	
	/**
	 * 
	 * @return
	 */
	public Map<Integer, VertexDescriptor> getVertexDescriptors() {
		return Collections.unmodifiableMap(vertexes);
	}
	
	/**
	 * Must be {@link org.apache.spark.Partitioner}. Unfortunately 
	 * @param partitioner
	 */
	public void addPartitioner(org.apache.spark.Partitioner partitioner) {
		this.partitioners.add(partitioner);
	}
	/**
	 * 
	 * @return
	 */
	public DAGTask build(Class<? extends Writable> keyClass, Class<? extends Writable> valueClass, Class<?> outputFormatClass, String outputPath){
		try {
			this.doBuild(keyClass, valueClass, outputFormatClass, outputPath);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return new DAGTask(){
			@Override
			public void execute(TezClient tezClient) {
				DAGBuilder.this.run(tezClient);
			}
		};
	}
	
	/**
	 * 
	 */
	private void run(TezClient tezClient){
	    try { 	
		    DAGClient dagClient = null;
//	        if (this.fileSystem.exists(new Path(outputPath))) {
//	          throw new FileAlreadyExistsException("Output directory " + this.outputPath + " already exists");
//	        }

	        tezClient.waitTillReady();
	       
	        if (logger.isInfoEnabled()){
	        	logger.info("Submitting generated DAG to YARN/Tez cluster");
	        }
	 
	        dagClient = tezClient.submitDAG(this.dag);

	
	        DAGStatus dagStatus =  dagClient.waitForCompletionWithStatusUpdates(null);
	        
	        if (logger.isInfoEnabled()){
	        	logger.info("DAG execution complete");
	        }
	        if (dagStatus.getState() != DAGStatus.State.SUCCEEDED) {
	          logger.error("DAG diagnostics: " + dagStatus.getDiagnostics());
	        }
	    } catch (Exception e) {
	    	e.printStackTrace();
	    	throw new IllegalStateException("Failed to execute DAG", e);
	    } 
	}

	/**
	 * 
	 * @param vd
	 */
	public void addVertex(VertexDescriptor vd) {
		vertexes.put(vd.getStageId(), vd);
	}
	
	/**
	 * 
	 */
	public String toString() {
		return vertexes.toString();
	}
	/**
	 * 
	 * @throws Exception
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void doBuild(Class<? extends Writable> keyClass, Class<? extends Writable> valueClass, 
			Class<?> outputFormatClass, String outputPath) throws Exception {
		Preconditions.checkState(keyClass != null, "'keyClass' must not be null");
		Preconditions.checkState(valueClass != null, "'valueClass' must not be null");
		Preconditions.checkState(outputFormatClass != null, "'outputFormatClass' must not be null");
		Preconditions.checkState(outputPath != null, "'outputPath' must not be null");
		Preconditions.checkState(this.vertexes.size() > 0, "VertexDescriptor definitions are missing. "
				+ "Can't build DAG. Use addVertex(..) method to add VertexDescriptors");
		if (logger.isDebugEnabled()){
			logger.debug("Building Tez DAG");
		}

		// This is for the interim edge which means that the intermediary data will always be written in universal form Key/ValueWritable
		// while the final data will be written as specified by the method (e.g., saveAsTex...)
		OrderedPartitionedKVEdgeConfig edgeConf = OrderedPartitionedKVEdgeConfig
			        .newBuilder("org.apache.spark.tez.io.KeyWritable", "org.apache.spark.tez.io.ValueWritable", 
			        		SparkDelegatingPartitioner.class.getName(), null).build();
		
		int sequenceCounter = 0;
		int counter = 0;
		FileSystem fs = FileSystem.get(this.tezConfiguration);
		for (Entry<Integer, VertexDescriptor> vertexDescriptorEntry : this.vertexes.entrySet()) {
			counter++;
			VertexDescriptor vertexDescriptor = vertexDescriptorEntry.getValue();
				
			//
			String vertexName = String.valueOf(sequenceCounter++);
			TezTask<?> task = vertexDescriptor.getTask();
			if (task instanceof VertexResultTask){
				((VertexResultTask)task).setKeyClass(keyClass);
				((VertexResultTask)task).setValueClass(valueClass);
			}
			
			UserPayload payload = TezHelper.serializeTask(vertexDescriptor, vertexName, fs, this.getNextPartitioner(), this.applicationName);
			//
			
			if (vertexDescriptor.getInput() instanceof TezRDD) {
				String inputPath = ((TezRDD<?,?>)vertexDescriptor.getInput()).getPath().toString();
				Class<?> inputFormatClass = ((TezRDD<?,?>)vertexDescriptor.getInput()).inputFormatClass();
				DataSourceDescriptor dataSource = MRInput.createConfigBuilder(new Configuration(tezConfiguration), inputFormatClass, inputPath).build();			
				
				String dsName = String.valueOf(sequenceCounter++);
				
				Vertex vertex = Vertex.create(vertexName, ProcessorDescriptor.create(SparkTaskProcessor.class.getName()).setUserPayload(payload))
						.addDataSource(dsName, dataSource);	
				// For single stage vertex we need to add data sink
				if (counter == vertexes.size()){
					JobConf dsConfig = new JobConf(tezConfiguration);
					dsConfig.setOutputKeyClass(keyClass);
					dsConfig.setOutputValueClass(valueClass);
					dsConfig.setOutputKeyClass(keyClass);
					dsConfig.setOutputValueClass(valueClass);
					DataSinkDescriptor dataSink = MROutput.createConfigBuilder(dsConfig, outputFormatClass, outputPath).build();
					vertex.addDataSink(dsName, dataSink);
				}
				
				vertex.addTaskLocalFiles(localResources);			
				dag.addVertex(vertex);
			}
			else if (vertexDescriptor.getInput() instanceof CacheRDD){
				String inputPath = ((CacheRDD<?>)vertexDescriptor.getInput()).getPath().toString();
				Class<?> inputFormatClass = SequenceFileInputFormat.class;
				DataSourceDescriptor dataSource = MRInput.createConfigBuilder(new Configuration(tezConfiguration), inputFormatClass, inputPath).build();			
				
				String dsName = String.valueOf(sequenceCounter++);
				
				Vertex vertex = Vertex.create(vertexName, ProcessorDescriptor.create(SparkTaskProcessor.class.getName()).setUserPayload(payload))
						.addDataSource(dsName, dataSource);	
				// For single stage vertex we need to add data sink
				if (counter == vertexes.size()){
					JobConf dsConfig = new JobConf(tezConfiguration);
					dsConfig.setOutputKeyClass(keyClass);
					dsConfig.setOutputValueClass(valueClass);
					dsConfig.setOutputKeyClass(keyClass);
					dsConfig.setOutputValueClass(valueClass);
					DataSinkDescriptor dataSink = MROutput.createConfigBuilder(dsConfig, outputFormatClass, outputPath).build();
					vertex.addDataSink(dsName, dataSink);
				}
				
				vertex.addTaskLocalFiles(localResources);			
				dag.addVertex(vertex);
			}
			else {
				if (counter == vertexes.size()) {
					JobConf dsConfig = new JobConf(tezConfiguration);
					dsConfig.setOutputKeyClass(keyClass);
					dsConfig.setOutputValueClass(valueClass);
					DataSinkDescriptor dataSink = MROutput.createConfigBuilder(dsConfig, outputFormatClass, outputPath).build();
					String dsName = String.valueOf(sequenceCounter++);
					Vertex vertex = Vertex.create(vertexName, ProcessorDescriptor.create(SparkTaskProcessor.class.getName()).setUserPayload(payload), 
							vertexDescriptor.getNumPartitions()).addDataSink(dsName, dataSink);
					vertex.addTaskLocalFiles(localResources);
					
					dag.addVertex(vertex);
					this.addEdges(vertexDescriptor, vertex, edgeConf);   
				}
				else {
					ProcessorDescriptor pd = ProcessorDescriptor.create(SparkTaskProcessor.class.getName()).setUserPayload(payload);
					Vertex vertex = Vertex.create(vertexName, pd, vertexDescriptor.getNumPartitions(), MRHelpers.getResourceForMRReducer(this.tezConfiguration));
					vertex.addTaskLocalFiles(localResources);
					
				    this.dag.addVertex(vertex);
				    this.addEdges(vertexDescriptor, vertex, edgeConf);
				}
			}
		}
		if (logger.isDebugEnabled()){
			logger.debug("Finished building Tez DAG");
		}
	}
	
	/**
	 * 
	 * @param vertexDescriptor
	 * @param targetVertex
	 * @param edgeConf
	 */
	@SuppressWarnings("unchecked")
	private void addEdges(VertexDescriptor vertexDescriptor, Vertex targetVertex, OrderedPartitionedKVEdgeConfig edgeConf){
		if (!(vertexDescriptor.getInput() instanceof String)) {
	    	for (int stageId : (Iterable<Integer>)vertexDescriptor.getInput()) {
	    		VertexDescriptor vd = vertexes.get(stageId);
		    	String vertexName =  vd.getVertexId() * 2 + "";
		    	Vertex v = dag.getVertex(vertexName);
		    	Edge edge = Edge.create(v, targetVertex, edgeConf.createDefaultEdgeProperty());
		    	this.dag.addEdge(edge);
			}
	    } 
	}
}
