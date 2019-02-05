//
//  WaveFormView.m
//  RNAudioRecorder
//
//  Created by Dev on 2019/2/2.
//  Copyright © 2019 Facebook. All rights reserved.
//

#import "WaveFormView.h"

@interface WaveFormView () {
    NSInteger mSamplesPerPixel;
    NSInteger mSampleRate;
    UIPanGestureRecognizer *panGesture;
}

@end

@implementation WaveFormView
@synthesize soundFile = _soundFile;
@synthesize offset = _offset;

- (id)initWithFrame:(CGRect)frame
{
    self = [super initWithFrame:frame];
    if (self) {
        // Initialization code
        _plotLineColor = [UIColor whiteColor];
        _timeTextSize = 12;
        _timeTextColor = [UIColor whiteColor];
        mSamplesPerPixel = 200;
        mSampleRate  = 14400;
        self.opaque = NO;
        [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(recordUpdated) name:kNotificationRecordingUpdate object:nil];
        panGesture = [[UIPanGestureRecognizer alloc] initWithTarget:self action:@selector(onPanGestureRecognize:)];
        panGesture.minimumNumberOfTouches = 1;
        panGesture.maximumNumberOfTouches = 1;
        [self addGestureRecognizer:panGesture];
    }
    return self;
}

- (void) onPanGestureRecognize:(UIPanGestureRecognizer*) gesture {
    CGPoint translation = [gesture translationInView:self];
    [self setOffset:[self offset] - translation.x];
    [gesture setTranslation:CGPointZero inView:self];
}

- (void) setOffset:(NSInteger)offset {
    if (_soundFile) {
        _offset = MAX(0, offset);
        _offset = MIN(_soundFile.plotArray.count, _offset);
    } else {
        _offset = 0;
    }
    [self setNeedsDisplay];
}

- (void) setSoundFile:(SoundFile *)soundFile {
    _soundFile = soundFile;
    mSamplesPerPixel = _soundFile.samplesPerPixel;
    mSampleRate = _soundFile.audioFormat.mSampleRate;
    _offset = 0;
}

- (void) recordUpdated {
    _offset = _soundFile.plotArray.count;
    dispatch_async(dispatch_get_main_queue(), ^{
        [self setNeedsDisplay];
    });
}

// Only override drawRect: if you perform custom drawing.
// An empty implementation adversely affects performance during animation.
- (void) drawRect:(CGRect)rect {
    // Drawing code
    CGContextRef c = UIGraphicsGetCurrentContext();
    CGContextSetLineWidth(c, 1);
    CGContextSetLineCap(c, kCGLineCapRound);
    CGContextSetLineJoin(c, kCGLineJoinRound);
    
    // Draw BaseLine
    CGContextSetStrokeColorWithColor(c, _plotLineColor.CGColor);
    
    CGFloat midY = CGRectGetMidY(rect);
    CGFloat midX = CGRectGetMidX(rect);
    CGFloat maxX = CGRectGetMaxX(rect);
    
    CGPoint baseLine[2] = {CGPointMake(0, midY), CGPointMake(CGRectGetMaxX(rect), midY)};
    CGContextAddLines(c, baseLine, 2);
    
    CGPoint nowLine[2] = {CGPointMake(midX, 0), CGPointMake(midX, CGRectGetMaxY(rect))};
    CGContextAddLines(c, nowLine, 2);
    CGContextStrokePath(c);
    
    // draw plot
    if (_soundFile) {
        for (NSInteger drawX = 0; drawX < CGRectGetWidth(rect); drawX++) {
            int plotIndex = drawX - midX + _offset;
            if (plotIndex < 0 ||
                plotIndex >= _soundFile.plotArray.count) {
                continue;
            }
            NSNumber *gain = _soundFile.plotArray[plotIndex];
            CGFloat height = midY * gain.integerValue / SHRT_MAX;
            CGPoint line[2] = {CGPointMake(drawX, midY - height), CGPointMake(drawX, midY + height)};
            CGContextAddLines(c, line, 2);
        }
    }
    CGContextStrokePath(c);
    
    // Draw Timeline
    // config with setting
    NSMutableDictionary *attribute = [NSMutableDictionary new];
    UIFont *timeFont = [UIFont systemFontOfSize:_timeTextSize];
    [attribute setObject:timeFont forKey:NSFontAttributeName];
    [attribute setObject:_timeTextColor forKey:NSForegroundColorAttributeName];
    CGFloat timeDrawOffset = [self widthOfString:@"00:00" withFont:timeFont] / 2;
    
    // Draw strings
    CGFloat startPoint = midX - _offset;
    int startSec = 0;
    startSec = -(startPoint) * mSamplesPerPixel / mSampleRate;
    startSec = MAX(0, startSec);
    CGFloat drawPoint = midX - _offset + startSec * mSampleRate / mSamplesPerPixel;
    while (drawPoint < maxX) {
        NSString *drawTime = [self makeShowTimeFromS:startSec];
        [drawTime drawAtPoint:CGPointMake(drawPoint - timeDrawOffset, 0) withAttributes:attribute];
        
        startSec++;
        drawPoint += mSampleRate / mSamplesPerPixel;
    }
}

- (NSString *) makeShowTimeFromS:(int) s{
    int min = s / 60;
    int ss = s % 60;
    return [NSString stringWithFormat:@"%d:%02d", min, ss];
}

- (CGFloat)widthOfString:(NSString *)string withFont:(UIFont *)font {
    NSDictionary *attributes = [NSDictionary dictionaryWithObjectsAndKeys:font, NSFontAttributeName, nil];
    return [[[NSAttributedString alloc] initWithString:string attributes:attributes] size].width;
}

@end