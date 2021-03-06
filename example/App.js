/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 * @flow
 */

import React, {Component} from 'react';
import {Platform, StyleSheet, Text, View, TouchableOpacity, Alert} from 'react-native';
import Permissions from 'react-native-permissions'

import AudioRecorder from './library_module'
// import AudioRecorder from 'react-native-audio-recorder'

var audioFile = Platform.OS === 'android' ? '/sdcard/Android/media/com.google.android.talk/Ringtones/hangouts_incoming_call.ogg' : '1.mp3'

type Props = {};
export default class App extends Component<Props> {

  constructor(props) {
    super(props)
    this.state = {
      initialized: false,
      hasPermissions: false,
      result: 'No Result',
      recordedFile:'',
      convertedFile: ''
    }
  }

  componentDidMount() {
    this.permissionCheck()
  }

  permissionCheck() {
    if (Platform.OS === 'android') {
      Permissions.checkMultiple(['microphone', 'storage'])
      .then(response => {        
        if (response.microphone !== 'authorized') {
          Permissions.request('microphone')
          .then(response => {
            if (response.storage !== 'authorized') {
              Permissions.request('storage')
              .then(response => {
                
              })
            }else{              
            }
          })
        } else {   
          if (response.storage !== 'authorized') {
            Permissions.request('storage')
            .then(response => {
            })
          }else{            
            this.setState({
              hasPermissions: true
            })
          }       
        }       
      })
    } else {
      Permissions.checkMultiple(['microphone', 'mediaLibrary'])
      .then(response => {  
        
        console.warn(response)      
        if (response.microphone !== 'authorized') {
          Permissions.request('microphone')
          .then(response => {
            if (response.mediaLibrary !== 'authorized') {
              Permissions.request('mediaLibrary')
              .then(response => {
              })
            }else{              
            }
          })
        } else {   
          if (response.mediaLibrary !== 'authorized') {
            Permissions.request('mediaLibrary')
            .then(response => {
              if (response == 'authorized') {
                this.setState({
                  hasPermissions: true
                })
              }
            })
          }else{
            this.setState({
              hasPermissions: true
            })
          }       
        }       
      })
    }
  }

  onPressPlay() {
    if (!this.state.initialized) {
      console.warn('Please call init method.')
      return
    }
    this.audioRecoder.play()
    .then(res => {
      this.setState({
        result: res,
        initialized: true
      })
    })
    .catch((err) => {
      this.setState({
        result: `error: ${err}`
      })
    })
  }

  onPressPause() {
    if (!this.state.initialized) {
      console.warn('Please call init method.')
      return
    }
    this.audioRecoder.pause()
    .then(res => {
      this.setState({
        result: `${res.position} ms`,
        initialized: true
      })
    })
    .catch((err) => {
      this.setState({
        result: `error: ${err}`
      })
    })
  }

  onPressStop() {
    if (!this.state.initialized) {
      console.warn('Please call init method.')
      return
    }
    this.audioRecoder.stopRecording()
      .then(res => {
        this.setState({
          recordedFile: res.filepath,
          result: `${res.filepath} : ${res.duration} ms`
        })
        audioFile = res.filepath
      })
      .catch((err) => {
        this.setState({
          result: `error: ${err}`
        })
      })
  }

  onPressStart() {
    if (!this.state.initialized) {
      console.warn('Please call init method.')
      return
    }
    this.audioRecoder.startRecording()
    .then(res => {
      this.setState({
        result: res,
        initialized: true
      })
    })
    .catch((err) => {
      this.setState({
        result: `error: ${err}`
      })
    })
  }

  onPressinitWithFile() {
    if (!this.state.hasPermissions) {
      Alert.alert(
        'Permission Errors',
        'Please make sure permissions enabled, and try again',
        [
          {text: 'Try Again', onPress:this.permissionCheck.bind(this)}
        ]
      )
      return
    }
    this.audioRecoder.initialize(audioFile, 2000)
    .then(res => {
      this.setState({
        result: res,
        initialized: true
      })
    })
    .catch((err) => {
      this.setState({
        result: `error: ${err}`
      })
    })
  }

  onPressRenderByFile() {
    if (!this.state.hasPermissions) {
      Alert.alert(
        'Permission Errors',
        'Please make sure permissions enabled, and try again',
        [
          {text: 'Try Again', onPress:this.permissionCheck.bind(this)}
        ]
      )
      return
    }

    this.audioRecoder.renderByFile(audioFile)
    .then(res => {
      this.setState({
        result: res,
        initialized: true
      })
    })
    .catch((err) => {
      this.setState({
        result: `error: ${err}`
      })
    })
  }

  onPressInit() {
    if (!this.state.hasPermissions) {
      Alert.alert(
        'Permission Errors',
        'Please make sure permissions enabled, and try again',
        [
          {text: 'Try Again', onPress:this.permissionCheck.bind(this)}
        ]
      )
      return
    }
    this.audioRecoder.initialize('', -1)
    .then(res => {
      this.setState({
        result: res,
        initialized: true
      })
    })
    .catch((err) => {
      this.setState({
        result: `error: ${err}`
      })
    })
  }

  onPressCut() {
    if (!this.state.hasPermissions) {
      Alert.alert(
        'Permission Errors',
        'Please make sure permissions enabled, and try again',
        [
          {text: 'Try Again', onPress:this.permissionCheck.bind(this)}
        ]
      )
      return
    }
    this.audioRecoder.cut(audioFile, 500, 2000)
    .then(res => {
      this.setState({
        result: `${res.filepath} : ${res.duration} ms`,
        recordedFile: res.filepath,
        initialized: true
      })
    })
    .catch((err) => {
      this.setState({
        result: `error: ${err}`
      })
    })
  }

  onPressConvert() {
    this.audioRecoder.compress(this.state.recordedFile)
    .then(res => {
      this.setState({
        result: `${res.filepath}`
      })
    })
    .catch((err) => {
      this.setState({
        result: `error: ${err}`
      })
    })
  }

  render() {
    return (
      <View style={styles.container}>
        <AudioRecorder
          style={{width: '75%', height: 200, backgroundColor: 'green'}}
          plotLineColor={'yellow'}
          timeTextColor={'white'}
          timeTextSize={12}
          onScrolled={(value) => console.warn(`onScrolled: ${value} ms`)}
          onScroll={true}
          pixelsPerSecond={50}
          ref={ref => this.audioRecoder = ref}
          onPlayFinished={() => console.warn('play finished')}
        />
        <View style={styles.buttonContainer}>
          <TouchableOpacity style={styles.button} onPress={this.onPressInit.bind(this)}>
            <Text style={{color: 'white'}}>init</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.button} onPress={this.onPressinitWithFile.bind(this)}>
            <Text style={{color: 'white'}}>initWithFile</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.button} onPress={this.onPressRenderByFile.bind(this)}>
            <Text style={{color: 'white'}}>renderByFile</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.button} onPress={this.onPressCut.bind(this)}>
            <Text style={{color: 'white'}}>Cut</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.buttonContainer}>
          <TouchableOpacity style={styles.button} onPress={this.onPressStart.bind(this)}>
            <Text style={{color: 'white'}}>Record</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.button} onPress={this.onPressStop.bind(this)}>
            <Text style={{color: 'white'}}>Stop Record</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.button} onPress={this.onPressPlay.bind(this)}>
            <Text style={{color: 'white'}}>play</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.button} onPress={this.onPressPause.bind(this)}>
            <Text style={{color: 'white'}}>pause</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.buttonContainer}>
          <TouchableOpacity style={styles.fullButton} onPress={this.onPressConvert.bind(this)}>
            <Text style={{color: 'white'}}>Convert</Text>
          </TouchableOpacity>
        </View>
        <Text>{this.state.result}</Text>
      </View>
    );
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#F5FCFF',
  },
  buttonContainer: {    
    flexDirection: 'row',
    width: '100%',
    justifyContent: 'space-around',
    marginVertical: 10
  },
  button: {
    height: 60,
    width: '25%',
    backgroundColor: 'blue',
    alignItems: 'center',
    justifyContent: 'center'
  },
  fullButton: {
    height: 60,
    width: '25%',
    backgroundColor: 'blue',
    alignItems: 'center',
    justifyContent: 'center'
  }
});
