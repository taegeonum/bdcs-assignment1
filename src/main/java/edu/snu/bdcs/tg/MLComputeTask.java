package edu.snu.bdcs.tg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.inject.Inject;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;

import com.microsoft.reef.driver.task.TaskConfigurationOptions;
import com.microsoft.reef.io.data.loading.api.DataSet;
import com.microsoft.reef.io.network.group.operators.Broadcast;
import com.microsoft.reef.io.network.group.operators.Reduce;
import com.microsoft.reef.io.network.nggroup.api.task.CommunicationGroupClient;
import com.microsoft.reef.io.network.nggroup.api.task.GroupCommClient;
import com.microsoft.reef.io.network.util.Pair;
import com.microsoft.reef.task.Task;
import com.microsoft.tang.annotations.Parameter;

import edu.snu.bdcs.tg.MLLauncher.Lambda;
import edu.snu.bdcs.tg.MLLauncher.LearningRate;
import edu.snu.bdcs.tg.MLLauncher.NumFeatures;
import edu.snu.bdcs.tg.MLLauncher.NumIterations;
import edu.snu.bdcs.tg.MLDriver.AllCommunicationGroup;
import edu.snu.bdcs.tg.groupcomm.operatornames.LossValueReducer;
import edu.snu.bdcs.tg.groupcomm.operatornames.ParameterVectorBroadcaster;
import edu.snu.bdcs.tg.groupcomm.operatornames.ParameterVectorReducer;
import edu.snu.bdcs.tg.vector.MLPair;
import edu.snu.bdcs.tg.vector.MLVector;

public class MLComputeTask implements Task {


  private final CommunicationGroupClient communicationGroupClient;
  private final Broadcast.Receiver<MLVector> paramBroadcaster;
  private final Reduce.Sender<MLVector> paramReducer;
  private final Reduce.Sender<Double> lossReducer;
  
  private final DataSet<LongWritable, Text> dataSet;
  private final int numFeatures;
  private MLVector parameters;
  private final double learningRate;
  private final int iterations;
  private final String identifier;
  private final double lambda;
  
  @Inject
  public MLComputeTask(final GroupCommClient groupCommClient,
      final DataSet<LongWritable, Text> dataSet, 
      final @Parameter(NumFeatures.class) int numFeatures, 
      final @Parameter(LearningRate.class) double learningRate, 
      final @Parameter(NumIterations.class) int iterations, 
      final @Parameter(TaskConfigurationOptions.Identifier.class) String identifier, 
      final @Parameter(Lambda.class) double lambda) {
    
    this.communicationGroupClient = groupCommClient.getCommunicationGroup(AllCommunicationGroup.class);
    this.paramBroadcaster = communicationGroupClient.getBroadcastReceiver(ParameterVectorBroadcaster.class);
    this.paramReducer = communicationGroupClient.getReduceSender(ParameterVectorReducer.class);
    this.lossReducer = communicationGroupClient.getReduceSender(LossValueReducer.class);
    this.dataSet = dataSet;
    this.numFeatures = numFeatures;
    this.learningRate = learningRate;
    this.iterations = iterations;
    this.identifier = identifier;
    this.lambda = lambda;
    
  }
  
  @Override
  public byte[] call(byte[] arg0) throws Exception {
    
    List<MLPair> trainingList = new ArrayList<>(100);

    for (final Pair<LongWritable, Text> keyValue : dataSet) {
      // key is byte number
      String value = keyValue.second.toString();
      String[] splited = value.split("\t");

      MLVector xArr = getFeatureVector(splited);
      int Y = Integer.valueOf(splited[splited.length - 1]).intValue() - 1;
      trainingList.add(new MLPair(xArr, Y));
    }

    
    for (int i = 0; i < iterations; i++) {
      // shuffle 
      long seed = System.nanoTime();
      Collections.shuffle(trainingList, new Random(seed));
      
      // get averaged parameter values
      parameters = paramBroadcaster.receive();

      // calculate loss function 
      double loss = MLFunction.getLoss(trainingList, parameters, lambda);
      lossReducer.send(new Double(loss));
      
      // gradient descent 
      parameters = MLFunction.getSGD(trainingList, parameters, learningRate, lambda);
      System.out.println("ComputeTask Sending parameter: " + parameters);
      paramReducer.send(parameters);
    }

    // calculate last loss function 
    double loss = MLFunction.getLoss(trainingList, parameters, lambda);
    lossReducer.send(new Double(loss));
    
    return null;
  }
  

  
  private MLVector getFeatureVector(String[] strs) {

    // last value is Y
    double[] dArr = new double[strs.length];
    dArr[0] = 1; 
    for (int i = 1; i < strs.length; i++) {
      dArr[i] = Integer.valueOf(strs[i-1]).intValue();
    }
    
    return new MLVector(dArr);
  }
  

  

}
