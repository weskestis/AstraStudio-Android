#include "FxPrimitives.h"
namespace astra::fx {
Coefficients biquad(int type,float frequency,float q,float gain,float rate) noexcept {
    const double w=2*std::numbers::pi_v<double>*std::clamp(frequency,10.0f,.45f*rate)/rate;
    const double c=std::cos(w),s=std::sin(w),a=s/(2*std::max(q,.1f)),A=dbGain(gain*.5f);
    double b0=1,b1=0,b2=0,a0=1,a1=0,a2=0;
    if(type==0){b0=1+a*A;b1=-2*c;b2=1-a*A;a0=1+a/A;a1=-2*c;a2=1-a/A;}
    else if(type==1||type==2){
        const double beta=2*std::sqrt(A)*a;
        if(type==1){b0=A*((A+1)-(A-1)*c+beta);b1=2*A*((A-1)-(A+1)*c);b2=A*((A+1)-(A-1)*c-beta);
            a0=(A+1)+(A-1)*c+beta;a1=-2*((A-1)+(A+1)*c);a2=(A+1)+(A-1)*c-beta;}
        else{b0=A*((A+1)+(A-1)*c+beta);b1=-2*A*((A-1)+(A+1)*c);b2=A*((A+1)+(A-1)*c-beta);
            a0=(A+1)-(A-1)*c+beta;a1=2*((A-1)-(A+1)*c);a2=(A+1)-(A-1)*c-beta;}
    }else{
        a0=1+a;a1=-2*c;a2=1-a;
        if(type==3){b0=(1-c)*.5f;b1=1-c;b2=b0;}
        else if(type==4){b0=(1+c)*.5f;b1=-(1+c);b2=b0;}
        else{b0=1;b1=-2*c;b2=1;}
    }
    return {b0/a0,b1/a0,b2/a0,a1/a0,a2/a0};
}
} // namespace astra::fx
