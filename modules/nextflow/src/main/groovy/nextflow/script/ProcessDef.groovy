/*
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.script

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Session
import nextflow.extension.ChannelFactory
/**
 * Models a nextflow process definition
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class ProcessDef extends BindableDef implements ChainableDef {

    private Session session = Global.session as Session

    private BaseScript owner

    private String name

    private ProcessConfig processConfig

    private TaskBody taskBody

    private Closure rawBody

    private Object output

    ProcessDef(BaseScript owner, Closure body, String name ) {
        this.owner = owner
        this.rawBody = body
        this.name = name
    }

    @Deprecated
    ProcessDef(BaseScript owner, String name, ProcessConfig config, TaskBody body) {
        this.owner = owner
        this.name = name
        this.taskBody = body
        this.processConfig = config
    }

    protected void config() {
        log.trace "Process config > $name"
        
        // the config object
        processConfig = new ProcessConfig(owner).setProcessName(name)

        // Invoke the code block which will return the script closure to the executed.
        // As side effect will set all the property declarations in the 'taskConfig' object.
        processConfig.throwExceptionOnMissingProperty(true)
        final copy = (Closure)rawBody.clone()
        copy.setResolveStrategy(Closure.DELEGATE_FIRST)
        copy.setDelegate(processConfig)
        taskBody = copy.call() as TaskBody
        processConfig.throwExceptionOnMissingProperty(false)
        if ( !taskBody )
            throw new IllegalArgumentException("Missing script in the specified process block -- make sure it terminates with the script string to be executed")

        // apply config settings to the process
        ProcessFactory.applyConfig(name, session.config, processConfig)
    }

    @Override
    ProcessDef clone() {
        def result = (ProcessDef)super.clone()
        result.@taskBody = taskBody?.clone()
        result.@processConfig = processConfig?.clone()
        result.@rawBody = (Closure)rawBody?.clone()
        return result
    }

    @Override
    ProcessDef withName(String name) {
        def result = clone()
        result.@name = name
        return result
    }

    private InputsList getDeclaredInputs() { processConfig.getInputs() }

    private OutputsList getDeclaredOutputs() { processConfig.getOutputs() }

    BaseScript getOwner() { owner }

    String getName() { name }

    def getOutput() { output }

    String getType() { 'process' }

    Object invoke_a(Object[] args) {
        if( processConfig==null )
            config()
        super.invoke_a(args)
    }

    @Override
    Object run(Object[] args) {
        final params = ChannelArrayList.spread(args)

        // sanity check
        if( params.size() != declaredInputs.size() )
            throw new IllegalArgumentException("Process `$name` declares ${declaredInputs.size()} input channels but ${params.size()} were specified")

        // set input channels
        for( int i=0; i<params.size(); i++ ) {
            declaredInputs[i].from(params[i])
        }

        // set output channels
        // note: the result object must be an array instead of a List to allow process
        // composition ie. to use the process output as the input in another process invocation
        List result = null
        if( declaredOutputs.size() ) {
            result = new ArrayList<>(declaredOutputs.size())
            final allScalarValues = declaredInputs.allScalarInputs()
            final hasEachParams = declaredInputs.any { it instanceof EachInParam }
            final singleton = allScalarValues && !hasEachParams

            for(int i=0; i<declaredOutputs.size(); i++ ) {
                final ch = ChannelFactory.create(singleton)
                result[i] = ch 
                declaredOutputs[i].into(ch)
            }
        }

        // create the executor
        final executor = session
                .executorFactory
                .getExecutor(name, processConfig, taskBody, session)

        // create processor class
        session
                .newProcessFactory(owner)
                .newTaskProcessor(name, executor, processConfig, taskBody)
                .run()

        // the result object
        if( !result )
            return output=null

        return output = (result.size()==1
                ? output=result[0]
                : new ChannelArrayList(result))
    }

}